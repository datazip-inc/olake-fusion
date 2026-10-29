/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Datazip Inc. in 2026
 */

package io.olake.fusion.server;

import io.olake.fusion.SingletonResourceUtil;
import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.catalog.CatalogTestHelpers;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.hive.HMSMockServer;
import io.olake.fusion.optimizer.standalone.StandaloneOptimizer;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.resource.ResourceContainer;
import io.olake.fusion.resource.ResourceGroup;
import io.olake.fusion.server.catalog.DefaultCatalogManager;
import io.olake.fusion.server.catalog.ServerCatalog;
import io.olake.fusion.server.manager.AbstractOptimizerContainer;
import io.olake.fusion.server.resource.Containers;
import io.olake.fusion.server.resource.OptimizerManager;
import io.olake.fusion.table.TableIdentifier;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.shade.guava32.com.google.common.io.MoreFiles;
import org.apache.amoro.shade.guava32.com.google.common.io.RecursiveDeleteOption;
import org.apache.amoro.shade.thrift.org.apache.thrift.transport.TTransportException;
import org.apache.commons.io.FileUtils;
import org.apache.iceberg.common.DynFields;
import org.junit.rules.TemporaryFolder;
import org.kohsuke.args4j.CmdLineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public class AmsEnvironment {

  private static final Logger LOG = LoggerFactory.getLogger(AmsEnvironment.class);
  private static AmsEnvironment integrationInstances = null;
  private final String rootPath;
  private static final String DEFAULT_ROOT_PATH = "/tmp/amoro_integration";
  private static final String OPTIMIZE_GROUP = "default";
  private final FusionServiceContainer serviceContainer;
  private DefaultCatalogManager catalogManager;
  private int tableServiceBindPort;
  private int optimizingServiceBindPort;
  private final HMSMockServer testHMS;

  public static final String INTERNAL_ICEBERG_CATALOG = "internal_iceberg_catalog";
  public static final String INTERNAL_ICEBERG_CATALOG_WAREHOUSE = "/internal_iceberg/warehouse";
  public static final String ICEBERG_CATALOG = "iceberg_catalog";
  public static String ICEBERG_CATALOG_DIR = "/iceberg/warehouse";
  private boolean started = false;
  private boolean optimizingStarted = false;
  private boolean singleton = false;

  public static AmsEnvironment getIntegrationInstances() {
    synchronized (AmsEnvironment.class) {
      if (integrationInstances == null) {
        TemporaryFolder baseDir = new TemporaryFolder();
        try {
          baseDir.create();
          integrationInstances = new AmsEnvironment(baseDir.newFolder().getAbsolutePath());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        integrationInstances.singleton();
      }
    }
    return integrationInstances;
  }

  public static void main(String[] args) throws Exception {
    AmsEnvironment amsEnvironment = new AmsEnvironment();
    amsEnvironment.start();
    amsEnvironment.startOptimizer();
  }

  public AmsEnvironment() throws Exception {
    this(DEFAULT_ROOT_PATH);
  }

  public AmsEnvironment(String rootPath) throws Exception {
    this.rootPath = rootPath;
    LOG.info("Initializing test AMS environment with root path: {}", rootPath);
    String path =
        Objects.requireNonNull(this.getClass().getClassLoader().getResource("")).getPath();
    FileUtils.writeStringToFile(
        new File(rootPath + "/conf/config.yaml"), getAmsConfig(), Charset.defaultCharset());
    FileUtils.writeStringToFile(
        new File(rootPath + "/conf/plugins/table-runtime-factories.yaml"),
        getTableRuntimeFactoriesConfig(),
        Charset.defaultCharset());
    System.setProperty(Environments.AMORO_HOME, rootPath);
    System.setProperty("derby.init.sql.dir", path + "../classes/sql/derby/");
    serviceContainer = new FusionServiceContainer();
    TemporaryFolder hiveDir = new TemporaryFolder();
    hiveDir.create();
    testHMS = new HMSMockServer(hiveDir.newFile());
  }

  public void singleton() {
    this.singleton = true;
  }

  public void start() throws Exception {
    if (started) {
      return;
    }

    testHMS.start();
    startAms();
    DynFields.UnboundField<DefaultCatalogManager> amsCatalogManagerField =
        DynFields.builder().hiddenImpl(FusionServiceContainer.class, "catalogManager").build();
    catalogManager = amsCatalogManagerField.bind(serviceContainer).get();
    initCatalog();
    started = true;
  }

  public void stop() throws IOException {
    if (!started) {
      return;
    }
    if (singleton && SingletonResourceUtil.isUseSingletonResource()) {
      return;
    }

    stopOptimizer();
    if (this.serviceContainer != null) {
      this.serviceContainer.dispose();
    }
    testHMS.stop();
    MoreFiles.deleteRecursively(Paths.get(rootPath), RecursiveDeleteOption.ALLOW_INSECURE);
  }

  public FusionServiceContainer serviceContainer() {
    return this.serviceContainer;
  }

  public boolean tableExist(TableIdentifier tableIdentifier) {
    ServerCatalog catalog = catalogManager.getServerCatalog(tableIdentifier.getCatalog());
    return catalog.tableExists(tableIdentifier.getDatabase(), tableIdentifier.getTableName());
  }

  public HMSMockServer getTestHMS() {
    return testHMS;
  }

  private void initCatalog() {
    createExternalIcebergCatalog();
    createInternalIceberg();
  }

  private void createInternalIceberg() {
    String warehouseDir = rootPath + INTERNAL_ICEBERG_CATALOG_WAREHOUSE;
    Map<String, String> properties = Maps.newHashMap();
    createDirIfNotExist(warehouseDir);
    properties.put(CatalogMetaProperties.KEY_WAREHOUSE, warehouseDir);
    CatalogMeta catalogMeta =
        CatalogTestHelpers.buildCatalogMeta(
            INTERNAL_ICEBERG_CATALOG,
            CatalogMetaProperties.CATALOG_TYPE_AMS,
            properties,
            TableFormat.ICEBERG);

    catalogManager.createCatalog(catalogMeta);
  }

  private void createExternalIcebergCatalog() {
    String warehouseDir = rootPath + ICEBERG_CATALOG_DIR;
    Map<String, String> properties = Maps.newHashMap();
    createDirIfNotExist(warehouseDir);
    properties.put(CatalogMetaProperties.KEY_WAREHOUSE, warehouseDir);
    CatalogMeta catalogMeta =
        CatalogTestHelpers.buildCatalogMeta(
            ICEBERG_CATALOG,
            CatalogMetaProperties.CATALOG_TYPE_HADOOP,
            properties,
            TableFormat.ICEBERG);
    catalogManager.createCatalog(catalogMeta);
  }

  private void createDirIfNotExist(String warehouseDir) {
    try {
      Files.createDirectories(Paths.get(warehouseDir));
    } catch (IOException e) {
      LOG.error("failed to create iceberg warehouse dir {}", warehouseDir, e);
      throw new RuntimeException(e);
    }
  }

  public void startOptimizer() {
    if (optimizingStarted) {
      return;
    }
    OptimizerManager optimizerManager = serviceContainer.getOptimizerManager();
    optimizerManager.createResourceGroup(
        new ResourceGroup.Builder("default", "localContainer")
            .addProperty("memory", "1024")
            .build());
    new Thread(
            () -> {
              String[] startArgs = {"-a", getOptimizingServiceUrl(), "-p", "1", "-g", "default"};
              try {
                StandaloneOptimizer.main(startArgs);
              } catch (CmdLineException e) {
                throw new RuntimeException(e);
              }
            })
        .start();
    optimizingStarted = true;
  }

  public void stopOptimizer() {
    DynFields.UnboundField<OptimizerManager> field =
        DynFields.builder().hiddenImpl(FusionServiceContainer.class, "optimizerManager").build();
    field
        .bind(serviceContainer)
        .get()
        .listOptimizers()
        .forEach(
            resource -> {
              ResourceContainer rc = Containers.get(resource.getContainerName());
              if (!(rc instanceof AbstractOptimizerContainer)) {
                LOG.warn(
                    "Cannot stop optimizer on non-optimizer resource container {}.",
                    resource.getContainerName());
                return;
              }

              ((AbstractOptimizerContainer) rc).releaseResource(resource);
            });
  }

  public String getTableServiceUrl() {
    return "thrift://127.0.0.1:" + tableServiceBindPort;
  }

  public String getOptimizingServiceUrl() {
    return "thrift://127.0.0.1:" + optimizingServiceBindPort;
  }

  public String getHttpUrl() {
    return "http://127.0.0.1:1630";
  }

  private void startAms() throws Exception {
    int retry = 10;
    while (true) {
      try {
        LOG.info("Starting test AMS...");
        genThriftBindPort();
        DynFields.UnboundField<Configurations> field =
            DynFields.builder().hiddenImpl(FusionServiceContainer.class, "serviceConfig").build();
        Configurations serviceConfig = field.bind(serviceContainer).get();
        serviceConfig.set(
            FusionManagementConf.TABLE_SERVICE_THRIFT_BIND_PORT, tableServiceBindPort);
        serviceConfig.set(
            FusionManagementConf.OPTIMIZING_SERVICE_THRIFT_BIND_PORT, optimizingServiceBindPort);
        serviceConfig.set(
            FusionManagementConf.REFRESH_EXTERNAL_CATALOGS_INTERVAL, Duration.ofMillis(1000L));
        serviceContainer.startRestServices();
        serviceContainer.startOptimizingService();
        LOG.info("Started test AMS.");
        break;
      } catch (TTransportException e) {
        if (e.getCause() instanceof BindException) {
          if (retry-- < 0) {
            throw e;
          } else {
            LOG.error("Started test AMS failed, retry later...", e);
            Thread.sleep(1000);
          }
        } else {
          throw e;
        }
      }
    }
  }

  private void genThriftBindPort() {
    // create a random port between 14000 - 18000
    Random random = new Random();
    this.tableServiceBindPort = random.nextInt(4000) + 14000;
    this.optimizingServiceBindPort = random.nextInt(4000) + 14000;
  }

  private String getAmsConfig() {
    return "ams:\n"
        + "  admin-username: \"admin\"\n"
        + "  admin-passowrd: \"admin\"\n"
        + "  server-bind-host: \"0.0.0.0\"\n"
        + "  server-expose-host: \"127.0.0.1\"\n"
        + "  refresh-external-catalog-interval: 180000 # 3min\n"
        + "  refresh-table-thread-count: 10\n"
        + "  refresh-table-interval: 60000 #1min\n"
        + "  expire-table-thread-count: 10\n"
        + "  clean-orphan-file-thread-count: 10\n"
        + "  sync-hive-tables-thread-count: 10\n"
        + "\n"
        + "  thrift-server:\n"
        + "    max-message-size: 104857600 # 100MB\n"
        + "    selector-thread-count: 2\n"
        + "    selector-queue-size: 4\n"
        + "    table-service:\n"
        + "      bind-port: 1260\n"
        + "      worker-thread-count: 20\n"
        + "    optimizing-service:\n"
        + "      bind-port: 1261\n"
        + "\n"
        + "  http-server:\n"
        + "    bind-port: 1630\n"
        + "\n"
        + "  self-optimizing:\n"
        + "    commit-thread-count: 10\n"
        + "    runtime-data-keep-days: 30\n"
        + "    runtime-data-expire-interval-hours: 1\n"
        + "    break-quota-limit-enabled: true\n"
        + "\n"
        + "  database:\n"
        + "    type: \"derby\"\n"
        + "    jdbc-driver-class: \"org.apache.derby.jdbc.EmbeddedDriver\"\n"
        + "    url: \"jdbc:derby:"
        + rootPath.replace("\\", "\\\\")
        + "/derby;create=true\"\n"
        + "\n"
        + "  terminal:\n"
        + "    backend: local\n"
        + "    local.spark.sql.session.timeZone: UTC\n"
        + "    local.spark.sql.iceberg.handle-timestamp-without-timezone: false\n"
        + "\n"
        + "containers:\n"
        + "  - name: localContainer\n"
        + "    container-impl: io.olake.fusion.server.manager.LocalOptimizerContainer\n"
        + "    properties:\n"
        + "      memory: \"1024\"\n"
        + "      hadoop_home: /opt/hadoop\n"
        + "      # java_home: /opt/java\n"
        + "\n"
        + "optimizer_groups:\n"
        + "  - name: "
        + OPTIMIZE_GROUP
        + "\n"
        + "    container: localContainer\n"
        + "    properties:\n"
        + "      memory: 1024 # MB\n"
        + "\n"
        + "metric-reporters:\n"
        + "  - name: mocked-reporter\n"
        + "    properties:\n";
  }

  private String getTableRuntimeFactoriesConfig() {
    return "table-runtime-factories:\n"
        + "  - name: default\n"
        + "    enabled: true\n"
        + "    priority: 100\n";
  }
}
