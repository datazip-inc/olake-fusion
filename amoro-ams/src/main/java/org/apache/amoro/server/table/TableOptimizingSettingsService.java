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
import org.apache.amoro.TableRuntime;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.TableOptimizingSettingsMeta;
import org.apache.amoro.server.persistence.mapper.TableOptimizingSettingsMapper;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.amoro.table.TableIdentifier;
import org.apache.amoro.table.TableProperties;
import org.apache.amoro.table.UnkeyedTable;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.UpdateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
public class TableOptimizingSettingsService extends PersistentBase {

  private static final Logger LOG = LoggerFactory.getLogger(TableOptimizingSettingsService.class);

  /** The table property keys this service owns. Nothing else is adopted, stripped or overlaid. */
  private static final List<String> OWNED_KEYS =
      Arrays.asList(
          TableProperties.ENABLE_SELF_OPTIMIZING,
          TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON,
          TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_CRON,
          TableProperties.SELF_OPTIMIZING_FULL_TRIGGER_CRON,
          TableProperties.SELF_OPTIMIZING_TARGET_SIZE);

  private static final TableOptimizingSettingsService INSTANCE =
      new TableOptimizingSettingsService();

  public static TableOptimizingSettingsService getInstance() {
    return INSTANCE;
  }

  /** One entry per table that has at least one override. Loaded once, then kept in step by hand. */
  private final Map<TableIdentifier, TableOptimizingSettingsMeta> settings =
      new ConcurrentHashMap<>();

  private volatile TableService tableService;

  private TableOptimizingSettingsService() {}

  /**
   * Loads every stored row. Call once during server startup, after the persistence layer is
   * initialized and before any table runtime is refreshed.
   */
  public void initialize() {
    settings.clear();
    getAs(TableOptimizingSettingsMapper.class, TableOptimizingSettingsMapper::selectAll)
        .forEach(row -> settings.put(scopeOf(row), row));
    LOG.info("Loaded optimizing settings for {} tables", settings.size());
  }

  /**
   * Wires the table service used to apply a change immediately. Set once the service exists, which
   * is later than {@link #initialize}; until then updates still persist and take effect on the next
   * refresh tick.
   */
  public void setTableService(TableService tableService) {
    this.tableService = tableService;
  }

  /**
   * Applies the stored settings on top of the given properties and returns a new map. The input is
   * never modified.
   */
  public Map<String, String> overlay(
      ServerTableIdentifier identifier, Map<String, String> properties) {
    Map<String, String> merged = Maps.newHashMap(properties);
    if (identifier == null) {
      return merged;
    }
    TableOptimizingSettingsMeta meta = settings.get(identifier.getIdentifier());
    if (meta == null) {
      return merged;
    }
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
  public TableOptimizingSettingsMeta get(ServerTableIdentifier identifier) {
    return settings.get(identifier.getIdentifier());
  }

  /**
   * Merges the given settings into every listed table and makes them effective right away.
   *
   * <p>Only the fields actually supplied are written; a field left null keeps whatever the table
   * already had, so a caller can change one cron without restating the rest. This also means the
   * API cannot clear an override back to the table's own value — that needs a separate operation.
   */
  public void update(
      Collection<ServerTableIdentifier> identifiers, TableOptimizingSettingsMeta values) {
    Preconditions.checkNotNull(values, "settings must not be null");
    for (ServerTableIdentifier identifier : identifiers) {
      TableOptimizingSettingsMeta meta = settings.get(identifier.getIdentifier());
      if (meta == null) {
        meta =
            new TableOptimizingSettingsMeta(
                identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());
      }
      merge(meta, values);
      persist(meta);
      applyNow(identifier);
    }
    LOG.info("Updated optimizing settings for {} tables: {}", identifiers.size(), values);
  }

  /** Copies across only the fields the caller actually set, leaving the rest untouched. */
  private static void merge(TableOptimizingSettingsMeta meta, TableOptimizingSettingsMeta values) {
    if (values.getSelfOptimizingEnabled() != null) {
      meta.setSelfOptimizingEnabled(values.getSelfOptimizingEnabled());
    }
    if (values.getMinorTriggerCron() != null) {
      meta.setMinorTriggerCron(values.getMinorTriggerCron());
    }
    if (values.getMajorTriggerCron() != null) {
      meta.setMajorTriggerCron(values.getMajorTriggerCron());
    }
    if (values.getFullTriggerCron() != null) {
      meta.setFullTriggerCron(values.getFullTriggerCron());
    }
    if (values.getTargetSize() != null) {
      meta.setTargetSize(values.getTargetSize());
    }
  }

  /**
   * One-time adoption of settings a table still carries in its own metadata: they are copied into
   * the database and then removed from the table, so from here on there is a single source of
   * truth. Only keys with no stored override are adopted, otherwise an {@code ALTER TABLE} could
   * silently win over the API.
   *
   * <p>Called on every refresh tick rather than once at startup, because AMS keeps discovering
   * tables long after boot and because the table has just been loaded anyway. It is self
   * terminating and costs nothing once done: the properties come from already-loaded metadata, so a
   * table with nothing left to adopt is five map lookups.
   */
  public void adoptTableProperties(ServerTableIdentifier identifier, AmoroTable<?> table) {
    UnkeyedTable unkeyed = (UnkeyedTable) table.originalTable();
    // The table's own metadata, not AmoroTable.properties(): that view has the catalog-level
    // `table.` defaults merged in, and those are neither ours to adopt nor possible to remove.
    Map<String, String> stored = ((HasTableOperations) unkeyed).operations().current().properties();
    List<String> present =
        OWNED_KEYS.stream().filter(stored::containsKey).collect(Collectors.toList());
    if (present.isEmpty()) {
      return;
    }

    TableOptimizingSettingsMeta meta = settings.get(identifier.getIdentifier());
    if (meta == null) {
      meta =
          new TableOptimizingSettingsMeta(
              identifier.getCatalog(), identifier.getDatabase(), identifier.getTableName());
    }
    adopt(meta, stored);
    // Persist before stripping, so a failed commit cannot lose the adopted values. A commit that
    // conflicts with a concurrent write just leaves the properties in place for the next tick.
    persist(meta);

    UpdateProperties update = unkeyed.updateProperties();
    present.forEach(update::remove);
    update.commit();
    LOG.info("Adopted {} from table {} into the AMS database", present, identifier);
  }

  private static void adopt(TableOptimizingSettingsMeta meta, Map<String, String> properties) {
    if (meta.getSelfOptimizingEnabled() == null
        && properties.containsKey(TableProperties.ENABLE_SELF_OPTIMIZING)) {
      meta.setSelfOptimizingEnabled(
          Boolean.parseBoolean(properties.get(TableProperties.ENABLE_SELF_OPTIMIZING)));
    }
    if (meta.getMinorTriggerCron() == null) {
      meta.setMinorTriggerCron(
          properties.get(TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON));
    }
    if (meta.getMajorTriggerCron() == null) {
      meta.setMajorTriggerCron(
          properties.get(TableProperties.SELF_OPTIMIZING_MAJOR_TRIGGER_CRON));
    }
    if (meta.getFullTriggerCron() == null) {
      meta.setFullTriggerCron(properties.get(TableProperties.SELF_OPTIMIZING_FULL_TRIGGER_CRON));
    }
    if (meta.getTargetSize() == null
        && properties.containsKey(TableProperties.SELF_OPTIMIZING_TARGET_SIZE)) {
      meta.setTargetSize(
          Long.parseLong(properties.get(TableProperties.SELF_OPTIMIZING_TARGET_SIZE)));
    }
  }

  /** Writes a row, or deletes it when it no longer overrides anything, and updates the cache. */
  private void persist(TableOptimizingSettingsMeta meta) {
    TableIdentifier scope = scopeOf(meta);
    if (meta.isEmpty()) {
      doAs(
          TableOptimizingSettingsMapper.class,
          mapper ->
              mapper.deleteSettings(meta.getCatalogName(), meta.getDbName(), meta.getTableName()));
      settings.remove(scope);
      return;
    }
    doAs(
        TableOptimizingSettingsMapper.class,
        mapper -> {
          if (mapper.updateSettings(meta) == 0) {
            mapper.insertSettings(meta);
          }
        });
    settings.put(scope, meta);
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

  private static TableIdentifier scopeOf(TableOptimizingSettingsMeta meta) {
    return TableIdentifier.of(meta.getCatalogName(), meta.getDbName(), meta.getTableName());
  }

}
