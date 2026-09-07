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
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.amoro.server.persistence.mapper.TableConfigurationsMapper;
import org.apache.amoro.server.persistence.mapper.TableMetaMapper;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.table.TableProperties;
import org.apache.amoro.table.UnkeyedTable;
import org.apache.amoro.utils.PropertyUtil;
import org.apache.iceberg.HasTableOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

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

  public Map<String, String> overlay(
      ServerTableIdentifier identifier, Map<String, String> properties) {
    Map<String, String> merged = Maps.newHashMap(properties);
    TableOptimizingConfigurationsMeta meta = select(identifier);
    if (meta == null) {
      return merged;
    }
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

  // either returns the existing table configurations for an iceberg table from db, or,
  // stores the entry in db for the new iceberg table, and returns
  public TableOptimizingConfigurationsMeta getOrCreate(ServerTableIdentifier identifier) {
    TableOptimizingConfigurationsMeta existing = select(identifier);
    if (existing != null) {
      return existing;
    }
    TableOptimizingConfigurationsMeta meta =
        new TableOptimizingConfigurationsMeta(
            identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());

    // store in db
    persist(meta);
    return meta;
  }

  public void update(
      Collection<ServerTableIdentifier> identifiers, TableOptimizingConfigurationsMeta values) {
    for (ServerTableIdentifier identifier : identifiers) {
      TableOptimizingConfigurationsMeta meta = select(identifier);
      if (meta == null) {
        meta =
            new TableOptimizingConfigurationsMeta(
                identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());
      }
      meta.setSelfOptimizingEnabled(values.getSelfOptimizingEnabled());
      meta.setMinorTriggerCron(values.getMinorTriggerCron());
      meta.setMajorTriggerCron(values.getMajorTriggerCron());
      meta.setFullTriggerCron(values.getFullTriggerCron());
      meta.setTargetSize(values.getTargetSize());
      // store in db
      persist(meta);
    }
    LOG.info("Updated optimizing settings for {} tables: {}", identifiers.size(), values);
  }

  // 1. Copies the existing configurations from the metadata.json into db (backward compatibility)
  // 2. Skips tables that already have been added in db
  // 3. Works for catalog already existing during Fusion restart
  // 4. Will not work for tables if someone manually modified the database-filter after
  //    startup (until Fusion is restarted)
  public void adoptExistingTables() {
    TableService service = this.tableService;
    if (service == null) {
      return;
    }
    for (ServerTableIdentifier identifier :
        getAs(TableMetaMapper.class, TableMetaMapper::selectAllTableIdentifiers)) {

      // continue if the iceberg table is already present in the database
      if (select(identifier) != null) {
        continue;
      }
      try {
        adoptTableProperties(identifier, service.loadTable(identifier));
      } catch (Exception e) {
        // must not stop for the others
        LOG.warn("Failed to adopt optimizing properties of table {}", identifier, e);
      }
    }
    LOG.info("Adopted optimizing properties for iceberg tables");
  }

  private void adoptTableProperties(ServerTableIdentifier identifier, AmoroTable<?> table) {
    UnkeyedTable unkeyed = (UnkeyedTable) table.originalTable();
    Map<String, String> stored = ((HasTableOperations) unkeyed).operations().current().properties();

    TableOptimizingConfigurationsMeta meta =
        new TableOptimizingConfigurationsMeta(
            identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());
    adopt(meta, stored);
    meta.setOlakeCreated(stored.containsKey(TableProperties.OLAKE_2PC));
    persist(meta);
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
    TableOptimizingConfigurationsMeta meta = select(identifier);
    if (meta == null) {
      meta =
          new TableOptimizingConfigurationsMeta(
              identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());
    }
    meta.setHealthScore(healthScore);
    meta.setHealthScoreSnapshotId(snapshotId);
    persist(meta);
  }

  public Long healthScoreSnapshotId(ServerTableIdentifier identifier) {
    TableOptimizingConfigurationsMeta meta = select(identifier);
    return meta == null ? null : meta.getHealthScoreSnapshotId();
  }

  public void deleteAllTablesOfCatalog(String catalogName) {
    doAs(TableConfigurationsMapper.class, mapper -> mapper.deleteAllTablesOfCatalog(catalogName));
    LOG.info("Removed optimizing configurations for all tables of dropped catalog {}", catalogName);
  }

  private void persist(TableOptimizingConfigurationsMeta meta) {
    doAs(
        TableConfigurationsMapper.class,
        mapper -> {
          if (mapper.updateSettings(meta) == 0) {
            mapper.insertSettings(meta);
          }
        });
  }

  private static void put(Map<String, String> properties, String key, String value) {
    if (value != null) {
      properties.put(key, value);
    }
  }
}
