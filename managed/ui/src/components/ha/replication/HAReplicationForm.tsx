import React, { FC, useRef } from 'react';
import { useMutation, useQueryClient } from 'react-query';
import { Alert, Col, Grid, Row } from 'react-bootstrap';
import * as Yup from 'yup';
import { Field, FieldProps, Form, Formik, FormikProps } from 'formik';
import { toast } from 'react-toastify';
import { YBButton, YBFormInput, YBSegmentedButtonGroup, YBToggle } from '../../common/forms/fields';
import { YBCopyButton } from '../../common/descriptors';
import { api, QUERY_KEY } from '../../../redesign/helpers/api';
import { HAConfig, HAReplicationSchedule } from '../../../redesign/helpers/dtos';
import YBInfoTip from '../../common/descriptors/YBInfoTip';
import './HAReplicationForm.scss';

export enum HAInstanceTypes {
  Active = 'Active',
  Standby = 'Standby'
}

const INITIAL_VALUES = {
  configId: '', // hidden field, needed to provide config ID to mutations as part of form values
  instanceType: HAInstanceTypes.Active,
  instanceAddress: window.location.origin,
  replicationFrequency: 1,
  clusterKey: '',
  replicationEnabled: true
};
type FormValues = typeof INITIAL_VALUES;

interface HAReplicationFormProps {
  config?: HAConfig;
  schedule?: HAReplicationSchedule;
  backToViewMode(): void;
}

const validationSchema = Yup.object().shape({
  instanceAddress: Yup.string()
    .required('Required field')
    .matches(/^(http|https):\/\/.+/i, 'Should be a valid URL'),
  clusterKey: Yup.string().required('Required field'),
  // fields below must be in DOM, otherwise conditional validation won't work
  replicationFrequency: Yup.mixed().when(['instanceType', 'replicationEnabled'], {
    is: (instanceType, replicationEnabled) =>
      instanceType === HAInstanceTypes.Active && replicationEnabled,
    then: Yup.number().min(1, 'Minimum value is ${min}').required('Required field')
  })
});

export const FREQUENCY_MULTIPLIER = 60000;

export const HAReplicationForm: FC<HAReplicationFormProps> = ({
  config,
  schedule,
  backToViewMode
}) => {
  let initialValues = INITIAL_VALUES;
  const isEditMode = !!config && !!schedule;

  const formik = useRef({} as FormikProps<FormValues>);
  const queryClient = useQueryClient();
  const generateKey = useMutation(api.generateHAKey, {
    onSuccess: (data) => formik.current.setFieldValue('clusterKey', data.cluster_key)
  });
  const { mutateAsync: enableReplication } = useMutation((formValues: FormValues) =>
    api.startHABackupSchedule(formValues.configId, formValues.replicationFrequency)
  );
  const { mutateAsync: disableReplication } = useMutation((formValues: FormValues) =>
    api.stopHABackupSchedule(formValues.configId)
  );
  const { mutateAsync: createHAConfig } = useMutation<HAConfig, unknown, FormValues>((formValues) =>
    api.createHAConfig(formValues.clusterKey)
  );
  const { mutateAsync: createHAInstance } = useMutation((formValues: FormValues) =>
    api.createHAInstance(
      formValues.configId,
      formValues.instanceAddress,
      formValues.instanceType === HAInstanceTypes.Active,
      true
    )
  );

  if (isEditMode && config && schedule) {
    const instance = config.instances.find((item) => item.is_local);
    if (instance) {
      initialValues = {
        configId: config.uuid,
        instanceType: instance.is_leader ? HAInstanceTypes.Active : HAInstanceTypes.Standby,
        instanceAddress: instance.address || '',
        replicationFrequency: schedule.frequency_milliseconds / FREQUENCY_MULTIPLIER,
        clusterKey: config.cluster_key,
        replicationEnabled: schedule.is_running
      };
    } else {
      toast.error("Can't find an HA platform instance with is_local = true");
    }
  }

  const submitForm = async (values: FormValues) => {
    const data = { ...values };
    data.replicationFrequency = data.replicationFrequency * FREQUENCY_MULTIPLIER;

    try {
      if (isEditMode) {
        // in edit mode only replication schedule could be edited
        if (data.replicationEnabled) {
          await enableReplication(data);
        } else {
          await disableReplication(data);
        }
      } else {
        if (data.instanceType === HAInstanceTypes.Active) {
          data.configId = (await createHAConfig(data)).uuid;
          await createHAInstance(data);
          if (data.replicationEnabled) {
            await enableReplication(data);
          }
        } else {
          data.configId = (await createHAConfig(data)).uuid;
          await createHAInstance(data);
        }
      }

      // invalidating queries will trigger their re-fetching and updating components where they are used
      queryClient.invalidateQueries(QUERY_KEY.getHAConfig);
      queryClient.invalidateQueries(QUERY_KEY.getHAReplicationSchedule);
      backToViewMode();
    } catch (error) {
      toast.error(`Error on ${isEditMode ? 'editing' : 'creating'} replication configuration`);
    } finally {
      formik.current.setSubmitting(false);
    }
  };

  return (
    <div className="ha-replication-form" data-testid="ha-replication-config-form">
      <Formik<FormValues>
        initialValues={initialValues}
        validationSchema={validationSchema}
        onSubmit={submitForm}
      >
        {(formikProps) => {
          // workaround for outdated version of Formik to access form methods outside of <Formik>
          formik.current = formikProps;

          return (
            <Form role="form">
              <Grid fluid>
                {formikProps.values.instanceType === HAInstanceTypes.Standby && !isEditMode && (
                  <Row className="ha-replication-form__alert">
                    <Col xs={12}>
                      <Alert bsStyle="warning">
                        Note: on standby instances you can only access the high availability configuration and other features won't be available until the configuration is deleted.
                      </Alert>
                    </Col>
                  </Row>
                )}
                <Row className="ha-replication-form__row">
                  <Col xs={2} className="ha-replication-form__label">
                    Instance Type
                  </Col>
                  <Col xs={10}>
                    <YBSegmentedButtonGroup
                      disabled={isEditMode}
                      name="instanceType"
                      options={[HAInstanceTypes.Active, HAInstanceTypes.Standby]}
                    />
                    <YBInfoTip
                      title="Replication Configuration"
                      content="The initial role for this platform instance"
                    />
                  </Col>
                </Row>
                <Row className="ha-replication-form__row">
                  <Col xs={2} className="ha-replication-form__label">
                    IP Address / Hostname
                  </Col>
                  <Col xs={10}>
                    <Field
                      name="instanceAddress"
                      type="text"
                      disabled={isEditMode}
                      component={YBFormInput}
                      placeholder="http://"
                      className="ha-replication-form__input"
                    />
                    <YBInfoTip
                      title="Replication Configuration"
                      content="The current platform's IP address or hostname"
                    />
                  </Col>
                </Row>
                <Row className="ha-replication-form__row">
                  <Col xs={2} className="ha-replication-form__label">
                    Shared Authentication Key
                  </Col>
                  <Col xs={10}>
                    <div className="ha-replication-form__key-input">
                      <Field
                        name="clusterKey"
                        type="text"
                        component={YBFormInput}
                        disabled={
                          isEditMode || formikProps.values.instanceType === HAInstanceTypes.Active
                        }
                        className="ha-replication-form__input"
                      />
                      <YBCopyButton text={formikProps.values.clusterKey} />
                    </div>
                    {formikProps.values.instanceType === HAInstanceTypes.Active && (
                      <YBButton
                        btnClass="btn btn-orange ha-replication-form__generate-key-btn"
                        btnText="Generate Key"
                        loading={generateKey.isLoading}
                        disabled={isEditMode || generateKey.isLoading}
                        onClick={generateKey.mutate}
                      />
                    )}
                    <YBInfoTip
                      title="Replication Configuration"
                      content={`The key used to authenticate the High Availability cluster ${
                        formikProps.values.instanceType === HAInstanceTypes.Standby
                          ? '(generated on active instance)'
                          : ''
                      }`}
                    />
                  </Col>
                </Row>
                <div
                  hidden={formikProps.values.instanceType === HAInstanceTypes.Standby}
                  data-testid="ha-replication-config-form-schedule-section"
                >
                  <Row className="ha-replication-form__row">
                    <Col xs={2} className="ha-replication-form__label">
                      Replication Frequency
                    </Col>
                    <Col xs={10}>
                      <Field
                        name="replicationFrequency"
                        type="number"
                        component={YBFormInput}
                        disabled={!formikProps.values.replicationEnabled}
                        className="ha-replication-form__input ha-replication-form__input--frequency"
                      />
                      <span>minute(s)</span>
                      <YBInfoTip
                        title="Replication Configuration"
                        content="How frequently periodic backups are sent to standby platforms"
                      />
                    </Col>
                  </Row>
                  <Row className="ha-replication-form__row">
                    <Col xs={2} className="ha-replication-form__label">
                      Enable Replication
                    </Col>
                    <Col xs={10}>
                      <Field name="replicationEnabled">
                        {({ field }: FieldProps) => (
                          <YBToggle
                            onToggle={formikProps.handleChange}
                            name="replicationEnabled"
                            input={{
                              value: field.value,
                              onChange: field.onChange
                            }}
                          />
                        )}
                      </Field>
                      <YBInfoTip
                        title="Replication Configuration"
                        content="Enable/disable replication to standby platforms"
                      />
                    </Col>
                  </Row>
                </div>
                <Row className="ha-replication-form__row">
                  <Col xs={12} className="ha-replication-form__footer">
                    {isEditMode && <YBButton btnText="Cancel" onClick={backToViewMode} />}
                    <YBButton
                      btnType="submit"
                      disabled={formikProps.isSubmitting || !formikProps.isValid}
                      loading={formikProps.isSubmitting}
                      btnClass="btn btn-orange"
                      btnText={isEditMode ? 'Save' : 'Create'}
                    />
                  </Col>
                </Row>
              </Grid>
            </Form>
          );
        }}
      </Formik>
    </div>
  );
};
