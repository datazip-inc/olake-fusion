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

package io.olake.fusion.server.dashboard.controller;

import static io.olake.fusion.properties.CatalogMetaProperties.CATALOG_TYPE_HIVE;

import io.javalin.http.Context;
import io.olake.fusion.Constants;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.api.OptimizingService;
import io.olake.fusion.client.OptimizingClientPools;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.hive.CachedHiveClientPool;
import io.olake.fusion.hive.HMSClientPool;
import io.olake.fusion.process.ProcessStatus;
import io.olake.fusion.properties.HiveTableProperties;
import io.olake.fusion.server.catalog.CatalogManager;
import io.olake.fusion.server.catalog.ServerCatalog;
import io.olake.fusion.server.dashboard.ServerTableDescriptor;
import io.olake.fusion.server.dashboard.ServerTableProperties;
import io.olake.fusion.server.dashboard.model.HiveTableInfo;
import io.olake.fusion.server.dashboard.model.TableMeta;
import io.olake.fusion.server.dashboard.model.TableOperation;
import io.olake.fusion.server.dashboard.model.UpgradeRunningInfo;
import io.olake.fusion.server.dashboard.model.UpgradeStatus;
import io.olake.fusion.server.dashboard.response.OkResponse;
import io.olake.fusion.server.dashboard.response.PageResult;
import io.olake.fusion.server.dashboard.utils.AmsUtil;
import io.olake.fusion.server.dashboard.utils.CommonUtil;
import io.olake.fusion.server.optimizing.OptimizingStatus;
import io.olake.fusion.server.persistence.TableRuntimeMeta;
import io.olake.fusion.server.process.TableProcessMeta;
import io.olake.fusion.server.table.TableManager;
import io.olake.fusion.table.TableIdentifier;
import io.olake.fusion.table.TableMetaStore;
import io.olake.fusion.table.TableProperties;
import io.olake.fusion.table.descriptor.AMSColumnInfo;
import io.olake.fusion.table.descriptor.ConsumerInfo;
import io.olake.fusion.table.descriptor.DDLInfo;
import io.olake.fusion.table.descriptor.FusionSnapshotsOfTable;
import io.olake.fusion.table.descriptor.OperationType;
import io.olake.fusion.table.descriptor.OptimizingProcessInfo;
import io.olake.fusion.table.descriptor.OptimizingTaskInfo;
import io.olake.fusion.table.descriptor.PartitionBaseInfo;
import io.olake.fusion.table.descriptor.PartitionFileBaseInfo;
import io.olake.fusion.table.descriptor.ServerTableMeta;
import io.olake.fusion.table.descriptor.TableSummary;
import io.olake.fusion.table.descriptor.TagOrBranchInfo;
import io.olake.fusion.utils.CatalogUtil;
import org.apache.amoro.shade.guava32.com.google.common.base.Function;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.amoro.shade.thrift.org.apache.thrift.TException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.iceberg.SnapshotRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/** The controller that handles table requests. */
public class TableController {
  private static final Logger LOG = LoggerFactory.getLogger(TableController.class);
  private static final long UPGRADE_INFO_EXPIRE_INTERVAL = 60 * 60 * 1000;

  private final CatalogManager catalogManager;
  private final TableManager tableManager;
  private final ServerTableDescriptor tableDescriptor;
  private final Configurations serviceConfig;
  private final ConcurrentHashMap<TableIdentifier, UpgradeRunningInfo> upgradeRunningInfo =
      new ConcurrentHashMap<>();
  private final ScheduledExecutorService tableUpgradeExecutor;

  public TableController(
      CatalogManager catalogManager,
      TableManager tableManager,
      ServerTableDescriptor tableDescriptor,
      Configurations serviceConfig) {
    this.catalogManager = catalogManager;
    this.tableManager = tableManager;
    this.tableDescriptor = tableDescriptor;
    this.serviceConfig = serviceConfig;
    this.tableUpgradeExecutor =
        Executors.newScheduledThreadPool(
            0,
            new ThreadFactoryBuilder()
                .setDaemon(false)
                .setNameFormat("async-hive-table-upgrade-%d")
                .build());
  }

  /**
   * get table detail.
   *
   * @param ctx - context for handling the request and response
   */
  public void getTableDetail(Context ctx) {

    String catalog = ctx.pathParam("catalog");
    String database = ctx.pathParam("db");
    String tableName = ctx.pathParam("table");

    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog)
            && StringUtils.isNotBlank(database)
            && StringUtils.isNotBlank(tableName),
        "catalog.database.tableName can not be empty in any element");
    Preconditions.checkState(catalogManager.catalogExist(catalog), "invalid catalog!");

    ServerTableMeta serverTableMeta =
        tableDescriptor.getTableDetail(
            TableIdentifier.of(catalog, database, tableName).buildTableIdentifier());
    TableSummary tableSummary = serverTableMeta.getTableSummary();
    Optional<ServerTableIdentifier> serverTableIdentifier =
        Optional.ofNullable(
            tableManager.getServerTableIdentifier(
                TableIdentifier.of(catalog, database, tableName).buildTableIdentifier()));
    if (serverTableIdentifier.isPresent()) {
      TableRuntimeMeta tableRuntimeMeta =
          tableManager.getTableRuntimeMata(serverTableIdentifier.get());
      if (tableRuntimeMeta != null) {
        OptimizingStatus status = OptimizingStatus.ofCode(tableRuntimeMeta.getStatusCode());
        if (status != null) {
          tableSummary.setOptimizingStatus(status.name());
        }
        io.olake.fusion.table.TableSummary summary = tableRuntimeMeta.getTableSummary();
        if (summary != null) {
          tableSummary.setHealthScore(summary.getHealthScore());
          tableSummary.setSmallFileScore(summary.getSmallFileScore());
          tableSummary.setEqualityDeleteScore(summary.getEqualityDeleteScore());
          tableSummary.setPositionalDeleteScore(summary.getPositionalDeleteScore());
        }
      }
    } else {
      tableSummary.setOptimizingStatus(OptimizingStatus.IDLE.name());
    }
    ctx.json(OkResponse.of(serverTableMeta));
  }

  /**
   * get hive table detail.
   *
   * @param ctx - context for handling the request and response
   */
  public void getHiveTableDetail(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog)
            && StringUtils.isNotBlank(db)
            && StringUtils.isNotBlank(table),
        "catalog.database.tableName can not be empty in any element");
    ServerCatalog serverCatalog = catalogManager.getServerCatalog(catalog);
    CatalogMeta catalogMeta = serverCatalog.getMetadata();
    TableMetaStore tableMetaStore = CatalogUtil.buildMetaStore(catalogMeta);
    HMSClientPool hmsClientPool =
        new CachedHiveClientPool(tableMetaStore, catalogMeta.getCatalogProperties());

    TableIdentifier tableIdentifier = TableIdentifier.of(catalog, db, table);
    HiveTableInfo hiveTableInfo;
    Table hiveTable = loadHmsTable(hmsClientPool, tableIdentifier);
    List<AMSColumnInfo> schema = transformHiveSchemaToAMSColumnInfo(hiveTable.getSd().getCols());
    List<AMSColumnInfo> partitionColumnInfos =
        transformHiveSchemaToAMSColumnInfo(hiveTable.getPartitionKeys());
    hiveTableInfo =
        new HiveTableInfo(
            tableIdentifier,
            TableMeta.TableType.HIVE,
            schema,
            partitionColumnInfos,
            new HashMap<>(),
            hiveTable.getCreateTime());
    ctx.json(OkResponse.of(hiveTableInfo));
  }

  /**
   * upgrade a hive table to mixed-hive table.
   *
   * @param ctx - context for handling the request and response
   */
  public void upgradeHiveTable(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog)
            && StringUtils.isNotBlank(db)
            && StringUtils.isNotBlank(table),
        "catalog.database.tableName can not be empty in any element");
    throw new IllegalStateException(
        "Upgrading a Hive table to mixed-hive is not supported, Fusion manages Iceberg tables only");
  }

  public void getUpgradeStatus(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    UpgradeRunningInfo info =
        upgradeRunningInfo.containsKey(TableIdentifier.of(catalog, db, table))
            ? upgradeRunningInfo.get(TableIdentifier.of(catalog, db, table))
            : new UpgradeRunningInfo(UpgradeStatus.NONE.toString());
    ctx.json(OkResponse.of(info));
  }

  /**
   * get table properties for upgrading hive table to mixed-hive table.
   *
   * @param ctx - context for handling the request and response
   */
  public void getUpgradeHiveTableProperties(Context ctx) throws IllegalAccessException {
    Map<String, String> keyValues = new TreeMap<>();
    Map<String, String> tableProperties =
        AmsUtil.getNotDeprecatedAndNotInternalStaticFields(TableProperties.class);
    tableProperties.keySet().stream()
        .filter(key -> !key.endsWith("_DEFAULT"))
        .forEach(
            key -> keyValues.put(tableProperties.get(key), tableProperties.get(key + "_DEFAULT")));
    ServerTableProperties.HIDDEN_EXPOSED.forEach(keyValues::remove);
    Map<String, String> hiveProperties =
        AmsUtil.getNotDeprecatedAndNotInternalStaticFields(HiveTableProperties.class);

    hiveProperties.keySet().stream()
        .filter(key -> HiveTableProperties.EXPOSED.contains(hiveProperties.get(key)))
        .filter(key -> !key.endsWith("_DEFAULT"))
        .forEach(
            key -> keyValues.put(hiveProperties.get(key), hiveProperties.get(key + "_DEFAULT")));
    ctx.json(OkResponse.of(keyValues));
  }

  /**
   * get list of optimizing processes.
   *
   * @param ctx - context for handling the request and response
   */
  public void getOptimizingProcesses(Context ctx) {

    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    String type = ctx.queryParam("type");

    if (StringUtils.isBlank(type)) {
      // treat all blank string to null
      type = null;
    }

    String status = ctx.queryParam("status");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);

    int offset = (page - 1) * pageSize;
    int limit = pageSize;
    Preconditions.checkArgument(offset >= 0, "offset[%s] must >= 0", offset);
    Preconditions.checkArgument(limit >= 0, "limit[%s] must >= 0", limit);

    TableIdentifier tableIdentifier = TableIdentifier.of(catalog, db, table);
    ProcessStatus processStatus =
        StringUtils.isBlank(status) ? null : ProcessStatus.valueOf(status);
    Pair<List<OptimizingProcessInfo>, Integer> optimizingProcessesInfo =
        tableDescriptor.getOptimizingProcessesInfo(
            tableIdentifier.buildTableIdentifier(), type, processStatus, limit, offset);
    List<OptimizingProcessInfo> result = optimizingProcessesInfo.getLeft();
    int total = optimizingProcessesInfo.getRight();

    ctx.json(OkResponse.of(PageResult.of(result, total)));
  }

  public void getOptimizingTypes(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    TableIdentifier tableIdentifier = TableIdentifier.of(catalog, db, table);

    Map<String, String> values =
        tableDescriptor.getTableOptimizingTypes(tableIdentifier.buildTableIdentifier());
    ctx.json(OkResponse.of(values));
  }

  /**
   * Get tasks of optimizing process.
   *
   * @param ctx - context for handling the request and response
   */
  public void getOptimizingProcessTasks(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    String processId = ctx.pathParam("processId");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);

    int offset = (page - 1) * pageSize;
    int limit = pageSize;
    Preconditions.checkArgument(offset >= 0, "offset[%s] must >= 0", offset);
    Preconditions.checkArgument(limit >= 0, "limit[%s] must >= 0", limit);

    TableIdentifier tableIdentifier = TableIdentifier.of(catalog, db, table);
    List<OptimizingTaskInfo> optimizingTaskInfos =
        tableDescriptor.getOptimizingProcessTaskInfos(
            tableIdentifier.buildTableIdentifier(), processId);

    PageResult<OptimizingTaskInfo> pageResult = PageResult.of(optimizingTaskInfos, offset, limit);
    ctx.json(OkResponse.of(pageResult));
  }

  /**
   * get list of snapshots.
   *
   * @param ctx - context for handling the request and response
   */
  public void getTableSnapshots(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String database = ctx.pathParam("db");
    String tableName = ctx.pathParam("table");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);
    // ref means tag/branch
    String ref = ctx.queryParamAsClass("ref", String.class).getOrDefault(null);
    String operation =
        ctx.queryParamAsClass("operation", String.class)
            .getOrDefault(OperationType.ALL.displayName());
    OperationType operationType = OperationType.of(operation);

    List<FusionSnapshotsOfTable> snapshotsOfTables =
        tableDescriptor.getSnapshots(
            TableIdentifier.of(catalog, database, tableName).buildTableIdentifier(),
            ref,
            operationType);
    int offset = (page - 1) * pageSize;
    PageResult<FusionSnapshotsOfTable> pageResult =
        PageResult.of(snapshotsOfTables, offset, pageSize);
    ctx.json(OkResponse.of(pageResult));
  }

  /**
   * get detail of snapshot.
   *
   * @param ctx - context for handling the request and response
   */
  public void getSnapshotDetail(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String database = ctx.pathParam("db");
    String tableName = ctx.pathParam("table");
    String snapshotId = ctx.pathParam("snapshotId");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);
    String ref = ctx.queryParamAsClass("ref", String.class).getOrDefault(null);

    List<PartitionFileBaseInfo> result =
        tableDescriptor.getSnapshotDetail(
            TableIdentifier.of(catalog, database, tableName).buildTableIdentifier(),
            snapshotId,
            ref);
    int offset = (page - 1) * pageSize;
    PageResult<PartitionFileBaseInfo> amsPageResult = PageResult.of(result, offset, pageSize);
    ctx.json(OkResponse.of(amsPageResult));
  }

  /**
   * get partition list.
   *
   * @param ctx - context for handling the request and response
   */
  public void getTablePartitions(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String database = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    String filter = ctx.queryParamAsClass("filter", String.class).getOrDefault("");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);

    List<PartitionBaseInfo> partitionBaseInfos =
        tableDescriptor.getTablePartition(
            TableIdentifier.of(catalog, database, table).buildTableIdentifier());
    partitionBaseInfos =
        partitionBaseInfos.stream()
            .filter(e -> e.getPartition().contains(filter))
            .sorted(Comparator.comparing(PartitionBaseInfo::getPartition).reversed())
            .collect(Collectors.toList());
    int offset = (page - 1) * pageSize;
    PageResult<PartitionBaseInfo> amsPageResult =
        PageResult.of(partitionBaseInfos, offset, pageSize);
    ctx.json(OkResponse.of(amsPageResult));
  }

  /**
   * get file list of some partition.
   *
   * @param ctx - context for handling the request and response
   */
  public void getPartitionFileListInfo(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    String partition = ctx.pathParam("partition");

    Integer specId = ctx.queryParamAsClass("specId", Integer.class).getOrDefault(0);
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);

    List<PartitionFileBaseInfo> partitionFileBaseInfos =
        tableDescriptor.getTableFile(
            TableIdentifier.of(catalog, db, table).buildTableIdentifier(), partition, specId);
    int offset = (page - 1) * pageSize;
    PageResult<PartitionFileBaseInfo> amsPageResult =
        PageResult.of(partitionFileBaseInfos, offset, pageSize);
    ctx.json(OkResponse.of(amsPageResult));
  }

  /**
   * get table operations.
   *
   * @param ctx - context for handling the request and response
   */
  public void getTableOperations(Context ctx) throws Exception {
    String catalogName = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String tableName = ctx.pathParam("table");

    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);
    int offset = (page - 1) * pageSize;

    List<DDLInfo> ddlInfoList =
        tableDescriptor.getTableOperations(
            TableIdentifier.of(catalogName, db, tableName).buildTableIdentifier());
    Collections.reverse(ddlInfoList);
    PageResult<TableOperation> amsPageResult =
        PageResult.of(ddlInfoList, offset, pageSize, TableOperation::buildFromDDLInfo);
    ctx.json(OkResponse.of(amsPageResult));
  }

  /**
   * get table list of catalog.db.
   *
   * @param ctx - context for handling the request and response
   */
  public void getTableList(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String keywords = ctx.queryParam("keywords");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog) && StringUtils.isNotBlank(db),
        "catalog.database can not be empty in any element");

    ServerCatalog serverCatalog = catalogManager.getServerCatalog(catalog);
    Function<TableFormat, String> formatToType =
        format -> {
          if (format.equals(TableFormat.MIXED_HIVE) || format.equals(TableFormat.MIXED_ICEBERG)) {
            return TableMeta.TableType.ARCTIC.toString();
          } else if (format.equals(TableFormat.PAIMON)) {
            return TableMeta.TableType.PAIMON.toString();
          } else if (format.equals(TableFormat.ICEBERG)) {
            return TableMeta.TableType.ICEBERG.toString();
          } else if (format.equals(TableFormat.HUDI)) {
            return TableMeta.TableType.HUDI.toString();
          } else {
            return format.toString();
          }
        };

    List<TableMeta> tables =
        serverCatalog.listTables(db).stream()
            .map(
                idWithFormat ->
                    new TableMeta(
                        idWithFormat.getIdentifier().getTableName(),
                        formatToType.apply(idWithFormat.getTableFormat())))
            // Sort by table format and table name
            .sorted(
                (table1, table2) -> {
                  if (Objects.equals(table1.getType(), table2.getType())) {
                    return table1.getName().compareTo(table2.getName());
                  } else {
                    return table1.getType().compareTo(table2.getType());
                  }
                })
            .collect(Collectors.toList());
    String catalogType = serverCatalog.getMetadata().getCatalogType();
    if (catalogType.equals(CATALOG_TYPE_HIVE)) {
      CatalogMeta catalogMeta = serverCatalog.getMetadata();
      TableMetaStore tableMetaStore = CatalogUtil.buildMetaStore(catalogMeta);
      HMSClientPool hmsClientPool =
          new CachedHiveClientPool(tableMetaStore, catalogMeta.getCatalogProperties());

      List<String> hiveTables = listHmsTables(hmsClientPool, db);
      Set<String> mixedHiveTables =
          tables.stream().map(TableMeta::getName).collect(Collectors.toSet());
      hiveTables.stream()
          .filter(e -> !mixedHiveTables.contains(e))
          .sorted(String::compareTo)
          .forEach(e -> tables.add(new TableMeta(e, TableMeta.TableType.HIVE.toString())));
    }

    ctx.json(
        OkResponse.of(
            tables.stream()
                .filter(t -> StringUtils.isBlank(keywords) || t.getName().contains(keywords))
                .collect(Collectors.toList())));
  }

  /**
   * get databases of some catalog.
   *
   * @param ctx - context for handling the request and response
   */
  public void getDatabaseList(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String keywords = ctx.queryParam("keywords");

    List<String> dbList =
        catalogManager.getServerCatalog(catalog).listDatabases().stream()
            .filter(item -> StringUtils.isBlank(keywords) || item.contains(keywords))
            .collect(Collectors.toList());
    ctx.json(OkResponse.of(dbList));
  }

  /**
   * get list of catalogs.
   *
   * @param ctx - context for handling the request and response
   */
  public void getCatalogs(Context ctx) {
    List<CatalogMeta> catalogs = catalogManager.listCatalogMetas();
    ctx.json(OkResponse.of(catalogs));
  }

  /**
   * get single page query token.
   *
   * @param ctx - context for handling the request and response
   */
  public void getTableDetailTabToken(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");

    String signCal = CommonUtil.generateTablePageToken(catalog, db, table);
    ctx.json(OkResponse.of(signCal));
  }

  public void getTableTags(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String database = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);
    List<TagOrBranchInfo> partitionBaseInfos =
        tableDescriptor.getTableTags(
            TableIdentifier.of(catalog, database, table).buildTableIdentifier());
    int offset = (page - 1) * pageSize;
    PageResult<TagOrBranchInfo> amsPageResult = PageResult.of(partitionBaseInfos, offset, pageSize);
    ctx.json(OkResponse.of(amsPageResult));
  }

  public void getTableBranches(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String database = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);
    List<TagOrBranchInfo> partitionBaseInfos =
        tableDescriptor.getTableBranches(
            TableIdentifier.of(catalog, database, table).buildTableIdentifier());
    putMainBranchFirst(partitionBaseInfos);
    int offset = (page - 1) * pageSize;
    PageResult<TagOrBranchInfo> amsPageResult = PageResult.of(partitionBaseInfos, offset, pageSize);
    ctx.json(OkResponse.of(amsPageResult));
  }

  public void getTableConsumerInfos(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String database = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    Integer page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
    Integer pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);
    List<ConsumerInfo> consumerInfos =
        tableDescriptor.getTableConsumersInfos(
            TableIdentifier.of(catalog, database, table).buildTableIdentifier());
    int offset = (page - 1) * pageSize;
    PageResult<ConsumerInfo> amsPageResult = PageResult.of(consumerInfos, offset, pageSize);
    ctx.json(OkResponse.of(amsPageResult));
  }

  /**
   * cancel the running optimizing process of one certain table.
   *
   * @param ctx - context for handling the request and response
   */
  public void cancelOptimizingProcess(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    String processIds = ctx.pathParam("processId");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog)
            && StringUtils.isNotBlank(db)
            && StringUtils.isNotBlank(table),
        "catalog.database.tableName can not be empty in any element");
    Preconditions.checkState(catalogManager.catalogExist(catalog), "invalid catalog!");
    long processId = Long.parseLong(processIds);
    ServerTableIdentifier serverTableIdentifier =
        tableManager.getServerTableIdentifier(
            TableIdentifier.of(catalog, db, table).buildTableIdentifier());
    TableProcessMeta processMeta = tableManager.getTableProcessMeta(processId);
    Preconditions.checkState(
        processMeta != null, "table process meta not found, processId: %s", processId);
    Preconditions.checkState(
        processMeta.getTableId() == serverTableIdentifier.getId(),
        "table process meta tableId not match, processId: %s, tableId: %s",
        processId,
        serverTableIdentifier.getId());

    OptimizingService.Iface client =
        OptimizingClientPools.getClient(
            AmsUtil.getAMSThriftAddress(serviceConfig, Constants.THRIFT_OPTIMIZING_SERVICE_NAME));
    try {
      client.cancelProcess(processId);
    } catch (TException e) {
      throw new IllegalStateException("Failed to cancel optimizing process:" + e.getMessage());
    }
    ctx.json(OkResponse.ok());
  }

  private void putMainBranchFirst(List<TagOrBranchInfo> branchInfos) {
    if (branchInfos.size() <= 1) {
      return;
    }
    branchInfos.stream()
        .filter(branch -> SnapshotRef.MAIN_BRANCH.equals(branch.getName()))
        .findFirst()
        .ifPresent(
            mainBranch -> {
              branchInfos.remove(mainBranch);
              branchInfos.add(0, mainBranch);
            });
  }

  private List<AMSColumnInfo> transformHiveSchemaToAMSColumnInfo(List<FieldSchema> fields) {
    return fields.stream()
        .map(
            f -> {
              AMSColumnInfo columnInfo = new AMSColumnInfo();
              columnInfo.setField(f.getName());
              columnInfo.setType(f.getType());
              columnInfo.setComment(f.getComment());
              return columnInfo;
            })
        .collect(Collectors.toList());
  }

  private static Table loadHmsTable(HMSClientPool hmsClientPool, TableIdentifier identifier) {
    try {
      return hmsClientPool.run(
          client -> client.getTable(identifier.getDatabase(), identifier.getTableName()));
    } catch (NoSuchObjectException e) {
      return null;
    } catch (org.apache.thrift.TException e) {
      throw new RuntimeException("Metastore operation failed for " + identifier, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted in call to getTable " + identifier, e);
    }
  }

  private static List<String> listHmsTables(HMSClientPool hmsClientPool, String database) {
    try {
      return hmsClientPool.run(client -> client.getAllTables(database));
    } catch (org.apache.thrift.TException e) {
      throw new RuntimeException("Failed to get tables of database " + database, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted in call to getAllTables", e);
    }
  }
}
