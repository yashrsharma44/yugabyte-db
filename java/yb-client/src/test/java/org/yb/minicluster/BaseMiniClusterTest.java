/**
 * Copyright (c) YugaByte, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing permissions and limitations
 * under the License.
 */
package org.yb.minicluster;

import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.AfterClass;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.BaseYBTest;
import org.yb.client.TestUtils;
import org.yb.util.SanitizerUtil;
import org.yb.util.Timeouts;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.Arrays;

import static org.yb.AssertionWrappers.fail;

/**
 * A base class for tests using a MiniCluster.
 */
public class BaseMiniClusterTest extends BaseYBTest {

  private static final Logger LOG = LoggerFactory.getLogger(BaseMiniClusterTest.class);

  // TODO: review the usage of the constants below.
  protected static final int NUM_MASTERS = 3;
  protected static final int NUM_TABLET_SERVERS = 3;

  protected static final int STANDARD_DEVIATION_FACTOR = 2;
  protected static final int DEFAULT_TIMEOUT_MS =
          (int) Timeouts.adjustTimeoutSecForBuildType(50000);

  /**
   * This is used as the default timeout when calling YB Java client's async API.
   */
  protected static final int DEFAULT_SLEEP = (int) Timeouts.adjustTimeoutSecForBuildType(50000);

  /**
   * A mini-cluster shared between invocations of multiple test methods.
   */
  protected static MiniYBCluster miniCluster;

  // Default master args to make sure we don't wait to trigger new LB tasks upon master leader
  // failover.
  protected static List<String> masterArgs = new ArrayList<>(
          Arrays.asList("--load_balancer_initial_delay_secs=0"));
  protected static List<String> tserverArgs = new ArrayList<String>();

  protected static Map<String, String> tserverEnvVars = new TreeMap<>();

  protected boolean useIpWithCertificate = MiniYBClusterParameters.DEFAULT_USE_IP_WITH_CERTIFICATE;

  protected String certFile = null;

  // The client cert files for mTLS.
  protected String clientCertFile = null;
  protected String clientKeyFile = null;

  // This is used as the default bind address (Used only for mTLS verification).
  protected String clientHost = null;
  protected int clientPort = 0;


  // Comma separate describing the master addresses and ports.
  protected static String masterAddresses;
  protected static List<HostAndPort> masterHostPorts;

  protected int getReplicationFactor() {
    return -1;
  }

  protected int getInitialNumMasters() {
    return -1;
  }

  protected int getInitialNumTServers() {
    return -1;
  }

  /** Subclasses can override this to set the number of shards per tablet server. */
  protected int getNumShardsPerTServer() {
    return MiniYBClusterParameters.DEFAULT_NUM_SHARDS_PER_TSERVER;
  }

  /** This allows subclasses to optionally skip the usage of a mini-cluster in a test. */
  protected boolean isMiniClusterEnabled() {
    return true;
  }

  /** Reset per-test settings to their default values, can be overridden to customize values. */
  protected void resetSettings() {}

  protected void customizeMiniClusterBuilder(MiniYBClusterBuilder builder) {
    Preconditions.checkNotNull(builder);
    // For sanitizer builds, it is easy to overload the master, leading to quorum changes.
    // This could end up breaking ever trivial DDLs like creating an initial table in the cluster.
    if (SanitizerUtil.isSanitizerBuild()) {
      builder.addMasterArgs("--leader_failure_max_missed_heartbeat_periods=10");
    }
  }

  /**
   * This makes sure that the mini cluster is up and running before each test. A test might opt to
   * leave the mini cluster running, and it will be reused by next tests, or it might shut down the
   * mini cluster by calling {@link BaseMiniClusterTest#destroyMiniCluster()}, and a new cluster
   * will be created for the next test.
   *
   * Even though {@link BaseMiniClusterTest#miniCluster} is a static variable, this logic is
   * implemented using {@link Before} and not {@link org.junit.BeforeClass}, because we need to know
   * the test class name so we can pass it as a command line parameter to master / tserver daemons
   * so we can better identify stuck processes.
   */
  @Before
  public void setUpBefore() throws Exception {
    resetSettings();
    if (!isMiniClusterEnabled()) {
      return;
    }
    TestUtils.clearReservedPorts();
    if (miniCluster == null) {
      createMiniCluster();
    } else if (shouldRestartMiniClusterBetweenTests()) {
      LOG.info("Restarting the MiniCluster");
      miniCluster.restart();
    }
  }

  protected boolean shouldRestartMiniClusterBetweenTests() {
    return false;
  }

  /**
   * A helper function to wait for existing tservers to heartbeat to master leader.
   * @return true if the number of tablet servers found is as expected
   */
  public boolean waitForTServersAtMasterLeader() throws Exception {
    if (!isMiniClusterEnabled()) {
      return true;
    }
    return miniCluster.waitForTabletServers(miniCluster.getTabletServers().size());
  }

  /**
   * Override this method to create a custom minicluster for your test.
   */
  protected void createMiniCluster() throws Exception {
    if (!isMiniClusterEnabled()) {
      return;
    }
    final int replicationFactor = getReplicationFactor();
    createMiniCluster(
        TestUtils.getFirstPositiveNumber(
            getInitialNumMasters(), replicationFactor, MiniYBClusterParameters.DEFAULT_NUM_MASTERS),
        TestUtils.getFirstPositiveNumber(
            getInitialNumTServers(), replicationFactor,
            MiniYBClusterParameters.DEFAULT_NUM_TSERVERS)
    );
  }

  /**
   * Creates a new cluster with the requested number of masters and tservers.
   */
  public void createMiniCluster(int numMasters, int numTservers) throws Exception {
    if (!isMiniClusterEnabled()) {
      return;
    }
    createMiniCluster(numMasters, Collections.nCopies(numTservers, tserverArgs), tserverEnvVars);
  }

  public void createMiniCluster(int numMasters, List<List<String>> tserverArgs) throws Exception {
    createMiniCluster(numMasters, tserverArgs, null);
  }

  public void createMiniCluster(int numMasters, List<List<String>> tserverArgs,
                                Map<String, String> tserverEnvVars)
      throws Exception {
    if (!isMiniClusterEnabled()) {
      return;
    }
    LOG.info("BaseMiniClusterTest.createMiniCluster is running");
    int numTservers = tserverArgs.size();
    MiniYBClusterBuilder clusterBuilder = new MiniYBClusterBuilder()
                      .numMasters(numMasters)
                      .numTservers(numTservers)
                      .defaultTimeoutMs(DEFAULT_SLEEP)
                      .testClassName(getClass().getName())
                      .masterArgs(masterArgs)
                      .perTServerArgs(tserverArgs)
                      .numShardsPerTServer(getNumShardsPerTServer())
                      .useIpWithCertificate(useIpWithCertificate)
                      .replicationFactor(getReplicationFactor())
                      .sslCertFile(certFile)
                      .sslClientCertFiles(clientCertFile, clientKeyFile)
                      .bindHostAddress(clientHost, clientPort);

    if (tserverEnvVars != null) {
      clusterBuilder.addEnvironmentVariables(tserverEnvVars);
    }

    customizeMiniClusterBuilder(clusterBuilder);
    miniCluster = clusterBuilder.build();
    masterAddresses = miniCluster.getMasterAddresses();
    masterHostPorts = miniCluster.getMasterHostPorts();

    LOG.info("Started cluster with {} masters and {} tservers. " +
             "Waiting for all tablet servers to heartbeat to masters...",
             numMasters, numTservers);
    if (!miniCluster.waitForTabletServers(numTservers)) {
      fail("Couldn't get " + numTservers + " tablet servers running, aborting.");
    }

    afterStartingMiniCluster();
  }

  public void createMiniCluster(int numMasters, List<String> masterArgs,
                                List<List<String>> tserverArgs)
      throws Exception {
    createMiniCluster(numMasters, masterArgs, tserverArgs, false);
  }

  public void createMiniCluster(int numMasters, List<String> masterArgs,
                                List<List<String>> tserverArgs,
                                boolean enablePgTransactions)
      throws Exception {
    if (!isMiniClusterEnabled()) {
      return;
    }
    LOG.info("BaseMiniClusterTest.createMiniCluster is running");
    int numTservers = tserverArgs.size();
    List<String> allMasterArgs = new ArrayList<>(masterArgs);
    allMasterArgs.addAll(this.masterArgs);
    miniCluster = new MiniYBClusterBuilder()
                      .numMasters(numMasters)
                      .numTservers(numTservers)
                      .defaultTimeoutMs(DEFAULT_SLEEP)
                      .testClassName(getClass().getName())
                      .masterArgs(allMasterArgs)
                      .useIpWithCertificate(useIpWithCertificate)
                      .perTServerArgs(tserverArgs)
                      .sslCertFile(certFile)
                      .sslClientCertFiles(clientCertFile, clientKeyFile)
                      .bindHostAddress(clientHost, clientPort)
                      .enablePgTransactions(enablePgTransactions)
                      .build();
    masterAddresses = miniCluster.getMasterAddresses();
    masterHostPorts = miniCluster.getMasterHostPorts();

    LOG.info("Started cluster with {} masters and {} tservers. " +
             "Waiting for all tablet servers to heartbeat to masters...",
             numMasters, numTservers);
    if (!miniCluster.waitForTabletServers(numTservers)) {
      fail("Couldn't get " + numTservers + " tablet servers running, aborting.");
    }

    afterStartingMiniCluster();
  }

  /**
   * This is called every time right after starting a mini cluster.
   */
  protected void afterStartingMiniCluster() throws Exception {
  }

  protected static void destroyMiniCluster() throws Exception {
    if (miniCluster != null) {
      LOG.info("Destroying mini cluster");
      miniCluster.shutdown();
      miniCluster = null;
    }
  }

  // Get metrics of all tservers.
  protected Map<MiniYBDaemon, Metrics> getAllMetrics() throws Exception {
    Map<MiniYBDaemon, Metrics> initialMetrics = new HashMap<>();
    for (MiniYBDaemon ts : miniCluster.getTabletServers().values()) {
      Metrics metrics = new Metrics(ts.getLocalhostIP(),
          ts.getCqlWebPort(),
          "server");
      initialMetrics.put(ts, metrics);
    }
    return initialMetrics;
  }

  protected IOMetrics createIOMetrics(MiniYBDaemon ts) throws Exception {
    return new IOMetrics(new Metrics(ts.getLocalhostIP(), ts.getWebPort(), "server"));
  }

  // Get IO metrics of all tservers.
  protected Map<MiniYBDaemon, IOMetrics> getTSMetrics() throws Exception {
    Map<MiniYBDaemon, IOMetrics> initialMetrics = new HashMap<>();
    for (MiniYBDaemon ts : miniCluster.getTabletServers().values()) {
      initialMetrics.put(ts, createIOMetrics(ts));
    }
    return initialMetrics;
  }

  // Get combined IO metrics of all tservers since a certain point.
  protected IOMetrics getCombinedMetrics(Map<MiniYBDaemon, IOMetrics> initialMetrics)
      throws Exception {
    IOMetrics totalMetrics = new IOMetrics();
    for (MiniYBDaemon ts : miniCluster.getTabletServers().values()) {
      IOMetrics metrics = createIOMetrics(ts).subtract(initialMetrics.get(ts));
      LOG.info("Metrics of " + ts.toString() + ": " + metrics.toString());
      totalMetrics.add(metrics);
    }
    LOG.info("Total metrics: " + totalMetrics.toString());
    return totalMetrics;
  }

  private Set<String> getTabletIds(String tableUUID)  throws Exception {
    return miniCluster.getClient().getTabletUUIDs(
        miniCluster.getClient().openTableByUUID(tableUUID));
  }

  protected int getTableCounterMetricByTableUUID(String tableUUID,
                                                 String metricName) throws Exception {
    int value = 0;
    Set<String> tabletIds = getTabletIds(tableUUID);
    for (MiniYBDaemon ts : miniCluster.getTabletServers().values()) {
      try {
        URL url = new URL(String.format("http://%s:%d/metrics",
            ts.getLocalhostIP(),
            ts.getWebPort()));
        Scanner scanner = new Scanner(url.openConnection().getInputStream());
        JsonParser parser = new JsonParser();
        JsonElement tree = parser.parse(scanner.useDelimiter("\\A").next());
        for (JsonElement elem : tree.getAsJsonArray()) {
          JsonObject obj = elem.getAsJsonObject();
          if (obj.get("type").getAsString().equals("tablet") &&
              tabletIds.contains(obj.get("id").getAsString())) {
            value += new Metrics(obj).getCounter(metricName).value;
          }
        }
      } catch (MalformedURLException e) {
        throw new InternalError(e.getMessage());
      }
    }
    return value;
  }

  protected RocksDBMetrics getRocksDBMetricByTableUUID(String tableUUID) throws Exception {
    Set<String> tabletIds = getTabletIds(tableUUID);
    RocksDBMetrics metrics = new RocksDBMetrics();
    for (MiniYBDaemon ts : miniCluster.getTabletServers().values()) {
      try {
        URL url = new URL(String.format("http://%s:%d/metrics",
            ts.getLocalhostIP(),
            ts.getWebPort()));
        Scanner scanner = new Scanner(url.openConnection().getInputStream());
        JsonParser parser = new JsonParser();
        JsonElement tree = parser.parse(scanner.useDelimiter("\\A").next());
        for (JsonElement elem : tree.getAsJsonArray()) {
          JsonObject obj = elem.getAsJsonObject();
          if (obj.get("type").getAsString().equals("tablet") &&
              tabletIds.contains(obj.get("id").getAsString())) {
            metrics.add(new RocksDBMetrics(new Metrics(obj)));
          }
        }
      } catch (MalformedURLException e) {
        throw new InternalError(e.getMessage());
      }
    }
    return metrics;
  }


  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    LOG.info("BaseMiniClusterTest.tearDownAfterClass is running");
    destroyMiniCluster();
    LOG.info("BaseMiniClusterTest.tearDownAfterClass completed");
  }

}
