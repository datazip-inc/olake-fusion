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
import org.apache.amoro.TableFormat;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.amoro.server.persistence.mapper.TableMetaMapper;
import org.apache.amoro.server.persistence.mapper.TableConfigurationsMapper;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.table.TableProperties;
import org.apache.amoro.table.UnkeyedTable;
import org.apache.amoro.utils.CommonUtil;
import org.apache.amoro.utils.PropertyUtil;
import org.apache.iceberg.HasTableOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

/**
 * Owns the self-optimizing settings that live in the AMS database rather than in the table's own
 * properties: {@code self-optimizing.enabled}, the three trigger crons and {@code
 * self-optimizing.target-size}.
 *
 * <p>Amoro resolves optimizing configuration by parsing a property map: the table's properties, on
 * top of the {@code table.}-prefixed catalog defaults, on top of the hard coded defaults in {@link
 * TableProperties}. This service adds one more layer on top of all of them, so a stored setting
 * wins over whatever the Iceberg table says. Within a row each column is independent: {@code null}
 * means "not overridden", so it falls through to the layer below.
 *
 * <p>The cache is authoritative once loaded, because AMS is the only writer. {@link
 * #update} refreshes it in the same call that writes, and then pushes the new configuration
 * straight into the live {@link DefaultTableRuntime}, so a change takes effect immediately rather
 * than at the next refresh tick.
 *
 * <p>A singleton because {@link DefaultTableRuntime} reaches it from {@code refresh}, and that
 * class is built by {@link DefaultTableRuntimeFactory} from nothing but a store — injecting it
 * would mean changing the table runtime plugin SPI. This mirrors {@code
 * SqlSessionFactoryProvider.getInstance()}.
 */
public class TableConfigurationsService extends PersistentBase {
  private static final Logger LOG = LoggerFactory.getLogger(TableConfigurationsService.class);
  private static final TableConfigurationsService INSTANCE =
      new TableConfigurationsService();

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
    put(
        merged,
        TableProperties.ENABLE_SELF_OPTIMIZING,
        meta.getSelfOptimizingEnabled() == null
            ? null
            : String.valueOf(meta.getSelfOptimizingEnabled()));
    put(merged, TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON, meta.getMinorTriggerCron());
    put(merged, TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_CRON, meta.getMajorTriggerCron());
    put(merged, TableProperties.SELF_OPTIMIZING_FULL_TRIGGER_CRON, meta.getFullTriggerCron());
    put(
        merged,
        TableProperties.SELF_OPTIMIZING_TARGET_SIZE,
        meta.getTargetSize() == null ? null : String.valueOf(meta.getTargetSize()));
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

  public TableOptimizingConfigurationsMeta getOrCreate(ServerTableIdentifier identifier) {
    TableOptimizingConfigurationsMeta existing = select(identifier);
    if (existing != null) {
      return existing;
    }
    TableOptimizingConfigurationsMeta meta =
        new TableOptimizingConfigurationsMeta(
            identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());

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
      merge(meta, values);
      persist(meta);
      applyNow(identifier);
    }
    LOG.info("Updated optimizing settings for {} tables: {}", identifiers.size(), values);
  }

  /** Copies across only the fields the caller actually set, leaving the rest untouched. */
  private static void merge(
      TableOptimizingConfigurationsMeta meta, TableOptimizingConfigurationsMeta values) {
    CommonUtil.setIfNotEmpty(values.getSelfOptimizingEnabled(), meta::setSelfOptimizingEnabled);
    CommonUtil.setIfNotEmpty(values.getMinorTriggerCron(), meta::setMinorTriggerCron);
    CommonUtil.setIfNotEmpty(values.getMajorTriggerCron(), meta::setMajorTriggerCron);
    CommonUtil.setIfNotEmpty(values.getFullTriggerCron(), meta::setFullTriggerCron);
    CommonUtil.setIfNotEmpty(values.getTargetSize(), meta::setTargetSize);
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
      if (!TableFormat.ICEBERG.equals(identifier.getFormat()) || select(identifier) != null) {
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

  /**
   * Copies a table's own metadata into {@code meta}, one field at a time, only where {@code meta}
   * does not already carry an override. {@link PropertyUtil#propertyAsNullableBoolean} and {@link
   * PropertyUtil#propertyAsNullableLong} are what let this stay a plain assignment instead of a
   * manual {@code containsKey} plus parse: both already return null when the property is absent,
   * which is exactly "leave the field null" here.
   */
  private static void adopt(
      TableOptimizingConfigurationsMeta meta, Map<String, String> properties) {
    if (meta.getSelfOptimizingEnabled() == null) {
      meta.setSelfOptimizingEnabled(
          PropertyUtil.propertyAsNullableBoolean(
              properties, TableProperties.ENABLE_SELF_OPTIMIZING));
    }
    if (meta.getMinorTriggerCron() == null) {
      meta.setMinorTriggerCron(properties.get(TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON));
    }
    if (meta.getMajorTriggerCron() == null) {
      meta.setMajorTriggerCron(properties.get(TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_CRON));
    }
    if (meta.getFullTriggerCron() == null) {
      meta.setFullTriggerCron(properties.get(TableProperties.SELF_OPTIMIZING_FULL_TRIGGER_CRON));
    }
    if (meta.getTargetSize() == null) {
      meta.setTargetSize(
          PropertyUtil.propertyAsNullableLong(
              properties, TableProperties.SELF_OPTIMIZING_TARGET_SIZE));
    }
  }

  /**
   * Records the health score the periodic refresh just evaluated, so the configurations API can
   * report it without evaluating anything itself.
   */
  public void storeHealthScore(ServerTableIdentifier identifier, int healthScore) {
    TableOptimizingConfigurationsMeta meta = select(identifier);
    if (meta == null) {
      meta =
          new TableOptimizingConfigurationsMeta(
              identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());
    }
    meta.setHealthScore(healthScore);
    persist(meta);
  }

  public void deleteCatalog(String catalogName) {
    doAs(
        TableConfigurationsMapper.class,
        mapper -> mapper.deleteCatalogSettings(catalogName));
    LOG.info("Removed optimizing settings of dropped catalog {}", catalogName);
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

  /**
   * Re-runs the normal refresh for one table so the new settings reach the runtime now instead of
   * on the next tick. Reusing {@link DefaultTableRuntime#refresh} is what keeps the config change
   * notifications intact: closing a running process when optimizing is switched off, and moving the
   * table between optimizer queues when the group changes.
   */
  private void applyNow(ServerTableIdentifier identifier) {
    TableService service = this.tableService;
    if (service == null || identifier.getId() == null) {
      return;
    }
    try {
      TableRuntime runtime = service.getRuntime(identifier.getId());
      if (runtime instanceof DefaultTableRuntime) {
        ((DefaultTableRuntime) runtime).refresh(service.loadTable(identifier));
      }
    } catch (Exception e) {
      // The settings are already stored, so the next refresh tick will pick them up anyway.
      LOG.warn("Failed to apply optimizing settings to table {} immediately", identifier, e);
    }
  }

  private static void put(Map<String, String> properties, String key, String value) {
    if (value != null) {
      properties.put(key, value);
    }
  }
}
