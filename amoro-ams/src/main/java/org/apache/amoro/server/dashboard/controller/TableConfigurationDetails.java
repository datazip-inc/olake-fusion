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

package org.apache.amoro.server.dashboard.controller;

import io.javalin.http.Context;
import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.TableFormat;
import org.apache.amoro.optimizing.OptimizingType;
import org.apache.amoro.server.catalog.CatalogManager;
import org.apache.amoro.server.catalog.ExternalCatalog;
import org.apache.amoro.server.dashboard.model.OptimizingConfigurations;
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.apache.amoro.server.dashboard.utils.AmsUtil;
import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.amoro.server.process.TableProcessMeta;
import org.apache.amoro.server.table.TableConfigurationsService;
import org.apache.amoro.server.table.TableManager;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.table.TableIdentifier;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotSummary;
import org.apache.iceberg.Table;
import org.apache.iceberg.util.PropertyUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads and writes the table configuratison in AMS db.
 *
 * <p>Only the configuration keys provided are updated, the rest are left intact.
 */
public class TableConfigurationDetails {
  private static final String CATALOG_KEY = "catalog";
  private static final String DB_KEY = "db";
  private static final String TABLE_KEY = "table";

  private static final int HEALTH_SCORE_NOT_CALCULATED = -1;

  private final CatalogManager catalogManager;
  private final TableManager tableManager;
  private final TableConfigurationsService configurations;

  public TableConfigurationDetails(CatalogManager catalogManager, TableManager tableManager) {
    this.catalogManager = catalogManager;
    this.tableManager = tableManager;
    this.configurations = TableConfigurationsService.getInstance();
  }

  public void getIcebergTables(Context ctx) {
    String catalog = ctx.pathParam(CATALOG_KEY);
    String db = ctx.pathParam(DB_KEY);
    check(catalog, db);

    // the tables the catalog holds right now, with the catalog's database and table filters leaving
    // out the same tables AMS leaves out when it syncs the catalog
    ExternalCatalog serverCatalog = (ExternalCatalog) catalogManager.getServerCatalog(catalog);
    List<String> names =
        serverCatalog.isDatabaseIncluded(db)
            ? serverCatalog.listTables(db).stream()
                .map(table -> table.getIdentifier().getTableName())
                .sorted()
                .collect(Collectors.toList())
            : Collections.emptyList();

    // a table with no configurations stored yet gets fresh ones
    Map<String, TableOptimizingConfigurationsMeta> stored =
        Maps.newHashMap(configurations.listByDatabase(catalog, db));
    List<String> unstored =
        names.stream().filter(name -> !stored.containsKey(name)).collect(Collectors.toList());
    if (!unstored.isEmpty()) {
      configurations.storeFresh(catalog, db, unstored);
      stored.putAll(configurations.listByDatabase(catalog, db));
    }

    // runs exist only for the tables AMS has synced from the catalog
    Map<String, Long> tableIds =
        tableManager.listIcebergTables(catalog, db).stream()
            .collect(
                Collectors.toMap(
                    ServerTableIdentifier::getTableName, ServerTableIdentifier::getId));
    Map<Long, Map<String, TableProcessMeta>> latestRuns =
        tableManager
            .listLatestProcessOfEachType(
                names.stream()
                    .map(tableIds::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()))
            .stream()
            .collect(
                Collectors.groupingBy(
                    TableProcessMeta::getTableId,
                    Collectors.toMap(TableProcessMeta::getProcessType, Function.identity())));

    List<Map<String, Object>> tables = Lists.newArrayList();
    for (String name : names) {
      TableOptimizingConfigurationsMeta config = stored.get(name);
      Map<String, TableProcessMeta> runs =
          latestRuns.getOrDefault(tableIds.get(name), Collections.emptyMap());

      Map<String, Object> table = new HashMap<>();
      table.put("name", name);
      table.put("olake_created", config.getOlakeCreated());
      table.put("enabled", config.getSelfOptimizingEnabled());
      table.put(
          "healthScore",
          config.getHealthScore() == null ? HEALTH_SCORE_NOT_CALCULATED : config.getHealthScore());
      table.put("minor", run(runs.get(OptimizingType.MINOR.name())));
      table.put("major", run(runs.get(OptimizingType.MAJOR.name())));
      table.put("full", run(runs.get(OptimizingType.FULL.name())));
      tables.add(table);
    }
    ctx.json(OkResponse.of(ImmutableMap.of("catalog", catalog, "database", db, "tables", tables)));
  }

  public void getTableConfig(Context ctx) {
    ServerTableIdentifier identifier = tableOf(ctx);
    TableOptimizingConfigurationsMeta meta = configurations.get(identifier);
    Map<String, Object> schedule = new HashMap<>();
    schedule.put("minorTriggerCron", meta.getMinorTriggerCron());
    schedule.put("majorTriggerCron", meta.getMajorTriggerCron());
    schedule.put("fullTriggerCron", meta.getFullTriggerCron());
    schedule.put("targetSize", meta.getTargetSize());
    // the one value read from the table's metadata.json
    schedule.put("tableSize", AmsUtil.byteToXB(totalSize(identifier)));
    ctx.json(OkResponse.of(schedule));
  }

  // whether optimizing is enabled and its crons, from db only, for a fast enable/disable toggle
  public void getOptimizing(Context ctx) {
    TableOptimizingConfigurationsMeta meta = configurations.get(tableOf(ctx));
    Map<String, Object> optimizing = new HashMap<>();
    optimizing.put("enabled", meta.getSelfOptimizingEnabled());
    optimizing.put("minorTriggerCron", meta.getMinorTriggerCron());
    optimizing.put("majorTriggerCron", meta.getMajorTriggerCron());
    optimizing.put("fullTriggerCron", meta.getFullTriggerCron());
    ctx.json(OkResponse.of(optimizing));
  }

  public void updateConfigurations(Context ctx) {
    String catalog = ctx.pathParam(CATALOG_KEY);
    String db = ctx.pathParam(DB_KEY);
    check(catalog, db);

    OptimizingConfigurations info = ctx.bodyAsClass(OptimizingConfigurations.class);

    List<ServerTableIdentifier> identifiers = Lists.newArrayList();
    for (String tableName : info.getTables()) {
      identifiers.add(identifierOf(catalog, db, tableName));
    }

    configurations.update(identifiers, info);
    ctx.json(OkResponse.ok());
  }

  // the latest run of one optimizing type, null when the table never ran it
  private static Map<String, Object> run(TableProcessMeta process) {
    if (process == null) {
      return null;
    }
    Map<String, Object> run = new HashMap<>();
    run.put("status", process.getStatus().name());
    run.put("runID", String.valueOf(process.getProcessId()));
    // a run still in progress has no finish time
    if (process.getFinishTime() > 0) {
      run.put("finish_time", process.getFinishTime());
    }
    return run;
  }

  private long totalSize(ServerTableIdentifier identifier) {
    Snapshot snapshot =
        ((Table) catalogManager.loadTable(identifier.getIdentifier()).originalTable())
            .currentSnapshot();
    return snapshot == null
        ? 0
        : PropertyUtil.propertyAsLong(snapshot.summary(), SnapshotSummary.TOTAL_FILE_SIZE_PROP, 0);
  }

  // the table named by the path params
  private ServerTableIdentifier tableOf(Context ctx) {
    String catalog = ctx.pathParam(CATALOG_KEY);
    String db = ctx.pathParam(DB_KEY);
    check(catalog, db);
    return identifierOf(catalog, db, ctx.pathParam(TABLE_KEY));
  }

  // the table as AMS synced it from the catalog, or by its name only when AMS has not synced it yet
  private ServerTableIdentifier identifierOf(String catalog, String db, String table) {
    ServerTableIdentifier identifier =
        tableManager.getServerTableIdentifier(
            TableIdentifier.of(catalog, db, table).buildTableIdentifier());
    return identifier != null
        ? identifier
        : ServerTableIdentifier.of(catalog, db, table, TableFormat.ICEBERG);
  }

  private void check(String catalog, String db) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog) && StringUtils.isNotBlank(db),
        "catalog.database can not be empty in any element");
    Preconditions.checkState(catalogManager.catalogExist(catalog), "invalid catalog!");
  }
}
