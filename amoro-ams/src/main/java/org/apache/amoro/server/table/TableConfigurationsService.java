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

package org.apache.amoro.server.table;

import org.apache.amoro.AmoroTable;
import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.server.catalog.CatalogManager;
import org.apache.amoro.server.dashboard.model.OptimizingConfigurations;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.amoro.server.persistence.mapper.TableConfigurationsMapper;
import org.apache.amoro.server.persistence.mapper.TableMetaMapper;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.table.TableProperties;
import org.apache.amoro.utils.PropertyUtil;
import org.apache.iceberg.HasTableOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Iceberg Table Configurations are now stored in the AMS database. This class is responsible
 * for it.
 */
public class TableConfigurationsService extends PersistentBase {
  private static final Logger LOG = LoggerFactory.getLogger(TableConfigurationsService.class);
  private static final TableConfigurationsService INSTANCE = new TableConfigurationsService();

  public static TableConfigurationsService getInstance() {
    return INSTANCE;
  }

  private volatile TableService tableService;

  private TableConfigurationsService() {}

  public void setTableService(TableService tableService) {
    this.tableService = tableService;
  }

  // the optimizing configurations of a table are always the ones stored in db (the defaults when
  // it has none stored), never the ones of its metadata.json
  public Map<String, String> overlay(
      ServerTableIdentifier identifier, Map<String, String> properties) {
    Map<String, String> merged = Maps.newHashMap(properties);
    TableOptimizingConfigurationsMeta meta = get(identifier);
    put(
        merged,
        TableProperties.ENABLE_SELF_OPTIMIZING,
        String.valueOf(meta.getSelfOptimizingEnabled()));
    put(merged, TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON, meta.getMinorTriggerCron());
    put(merged, TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_CRON, meta.getMajorTriggerCron());
    put(merged, TableProperties.SELF_OPTIMIZING_FULL_TRIGGER_CRON, meta.getFullTriggerCron());
    put(merged, TableProperties.SELF_OPTIMIZING_TARGET_SIZE, String.valueOf(meta.getTargetSize()));
    return merged;
  }

  /** Returns the stored overrides for a table, or null when it has none. */
  private TableOptimizingConfigurationsMeta select(ServerTableIdentifier identifier) {
    return getAs(
        TableConfigurationsMapper.class,
        mapper ->
            mapper.selectScope(
                identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName()));
  }

  private static TableOptimizingConfigurationsMeta newMeta(ServerTableIdentifier identifier) {
    return new TableOptimizingConfigurationsMeta(
        identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());
  }

  // stores fresh configurations for the tables that have none stored yet, a table already stored
  // (one recreated under the same name too) keeps the configurations it has
  public void storeFresh(String catalogName, String dbName, Collection<String> tableNames) {
    doAs(
        TableConfigurationsMapper.class,
        mapper -> mapper.insertFresh(catalogName, dbName, tableNames));
  }

  /** Returns the stored configurations of the tables of one database, by table name. */
  public Map<String, TableOptimizingConfigurationsMeta> listByDatabase(
      String catalogName, String dbName) {
    return getAs(
            TableConfigurationsMapper.class, mapper -> mapper.selectByDatabase(catalogName, dbName))
        .stream()
        .collect(
            Collectors.toMap(TableOptimizingConfigurationsMeta::getTableName, Function.identity()));
  }

  // returns the stored configurations of the table, or the defaults when it has none stored,
  // without storing them: only the startup adoption, a new table and an update store them
  public TableOptimizingConfigurationsMeta get(ServerTableIdentifier identifier) {
    return Optional.ofNullable(select(identifier)).orElseGet(() -> newMeta(identifier));
  }

  public void update(
      Collection<ServerTableIdentifier> identifiers, OptimizingConfigurations values) {
    for (ServerTableIdentifier identifier : identifiers) {
      // the configurations not provided keep their stored value
      TableOptimizingConfigurationsMeta meta = get(identifier);
      values.applyTo(meta);
      // store in db, only the configuration columns so a concurrent health score write is kept
      persist(meta, TableConfigurationsMapper::updateConfigurations);
      LOG.info("Updated optimizing settings: {}", meta);
      applyToRuntime(identifier);
    }
  }

  // the table runtime takes the stored configurations right away rather than on its next refresh,
  // so optimizing never works on configurations the db no longer holds
  private void applyToRuntime(ServerTableIdentifier identifier) {
    TableService service = tableService;
    // a table AMS has not synced from the catalog yet has no runtime, it starts on the stored ones
    if (service == null || identifier.getId() == null || !service.contains(identifier.getId())) {
      return;
    }
    try {
      ((DefaultTableRuntime) service.getRuntime(identifier.getId())).applyStoredConfigurations();
    } catch (Exception e) {
      // stored already, its next refresh applies them
      LOG.warn("Failed to apply the optimizing settings to the runtime of {}", identifier, e);
    }
  }

  // 1. Copies the existing configurations from the metadata.json into db (backward compatibility)
  // 2. Skips tables that already have been added in db
  // 3. Works for catalog already existing during Fusion restart
  // 4. Runs at startup only. Will not work for tables with configurations in its metadata.json
  // introduced after startup.
  // 5. A table that failed to load keeps no entry, and is tried again on the next startup
  public void adoptExistingTables(CatalogManager catalogManager) {
    for (ServerTableIdentifier identifier :
        getAs(TableMetaMapper.class, TableMetaMapper::selectAllTableIdentifiers)) {

      // continue if the iceberg table is already present in the database
      if (select(identifier) != null) {
        continue;
      }
      try {
        adoptTableProperties(identifier, catalogManager.loadTable(identifier.getIdentifier()));
      } catch (Exception e) {
        // must not stop for the others
        LOG.warn("Failed to adopt optimizing properties of table {}", identifier, e);
      }
    }
    LOG.info("Adopted optimizing properties for iceberg tables");
  }

  private void adoptTableProperties(ServerTableIdentifier identifier, AmoroTable<?> table) {
    Map<String, String> stored = properties(table);

    TableOptimizingConfigurationsMeta meta = newMeta(identifier);
    adopt(meta, stored);
    meta.setOlakeCreated(stored.containsKey(TableProperties.OLAKE_2PC));
    persist(meta, TableConfigurationsMapper::updateSettings);
  }

  /**
   * Marks a stored table as created by OLake once OLake has committed to it: OLake writes its
   * property with its first data commit, which may come after the table was stored. Once marked, a
   * table stays marked.
   */
  public void storeOlakeCreated(ServerTableIdentifier identifier, AmoroTable<?> table) {
    if (!properties(table).containsKey(TableProperties.OLAKE_2PC)) {
      return;
    }
    // a table with no row stored keeps none
    doAs(
        TableConfigurationsMapper.class,
        mapper ->
            mapper.markOlakeCreated(
                identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName()));
  }

  // the table's metadata.json properties as the catalog holds them, without catalog level defaults
  // merged in
  private static Map<String, String> properties(AmoroTable<?> table) {
    return ((HasTableOperations) table.originalTable()).operations().current().properties();
  }

  private static void adopt(
      TableOptimizingConfigurationsMeta meta, Map<String, String> properties) {
    meta.setSelfOptimizingEnabled(
        PropertyUtil.propertyAsBoolean(properties, TableProperties.ENABLE_SELF_OPTIMIZING, false));
    meta.setMinorTriggerCron(properties.get(TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON));
    meta.setMajorTriggerCron(properties.get(TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_CRON));
    meta.setFullTriggerCron(properties.get(TableProperties.SELF_OPTIMIZING_FULL_TRIGGER_CRON));
    meta.setTargetSize(
        PropertyUtil.propertyAsLong(
            properties,
            TableProperties.SELF_OPTIMIZING_TARGET_SIZE,
            TableOptimizingConfigurationsMeta.DEFAULT_TARGET_SIZE));
  }

  public void storeHealthScore(ServerTableIdentifier identifier, int healthScore, long snapshotId) {
    TableOptimizingConfigurationsMeta meta = newMeta(identifier);
    meta.setHealthScore(healthScore);
    meta.setHealthScoreSnapshotId(snapshotId);
    // only the health score columns of a stored table, so a configuration saved meanwhile is not
    // overwritten, and a table is never stored with defaults ahead of its own configurations
    doAs(TableConfigurationsMapper.class, mapper -> mapper.updateHealthScore(meta));
  }

  public void deleteAllTablesOfCatalog(String catalogName) {
    doAs(TableConfigurationsMapper.class, mapper -> mapper.deleteAllTablesOfCatalog(catalogName));
    LOG.info("Removed optimizing configurations for all tables of dropped catalog {}", catalogName);
  }

  // runs the given update on the existing row, or inserts the full row when the table has none
  private void persist(
      TableOptimizingConfigurationsMeta meta,
      BiFunction<TableConfigurationsMapper, TableOptimizingConfigurationsMeta, Integer> update) {
    doAs(
        TableConfigurationsMapper.class,
        mapper -> {
          if (update.apply(mapper, meta) == 0) {
            mapper.insertSettings(meta);
          }
        });
  }

  // a cron not set is removed, so the one of the metadata.json does not apply
  private static void put(Map<String, String> properties, String key, String value) {
    if (value == null) {
      properties.remove(key);
    } else {
      properties.put(key, value);
    }
  }
}
