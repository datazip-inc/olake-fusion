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
import io.javalin.http.HttpCode;
import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.config.OptimizingConfig;
import org.apache.amoro.server.catalog.CatalogManager;
import org.apache.amoro.server.dashboard.response.ErrorResponse;
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.apache.amoro.server.persistence.TableOptimizingSettingsMeta;
import org.apache.amoro.server.persistence.TableRuntimeMeta;
import org.apache.amoro.server.table.TableConfigurations;
import org.apache.amoro.server.table.TableManager;
import org.apache.amoro.server.table.TableOptimizingSettingsService;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.table.TableIdentifier;
import org.apache.amoro.utils.CronUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Reads and writes the self-optimizing settings AMS owns in its own database rather than in the
 * Iceberg table's properties.
 *
 * <p>An update applies the same settings to a list of tables at once and takes effect immediately.
 * Only the settings present in the body are written; anything left out keeps its current value.
 */
public class OptimizingSettingsController {

  private final CatalogManager catalogManager;
  private final TableManager tableManager;
  private final TableOptimizingSettingsService settingsService;

  public OptimizingSettingsController(CatalogManager catalogManager, TableManager tableManager) {
    this.catalogManager = catalogManager;
    this.tableManager = tableManager;
    this.settingsService = TableOptimizingSettingsService.getInstance();
  }

  /** Returns the stored overrides and the effective config of every table of one database. */
  public void getDatabaseSettings(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    checkDatabase(catalog, db);

    List<TableOptimizingSettings> result = Lists.newArrayList();
    for (ServerTableIdentifier identifier : tableManager.listManagedTables(catalog, db)) {
      result.add(
          new TableOptimizingSettings(
              identifier, settingsService.get(identifier), effectiveOf(identifier)));
    }
    ctx.json(OkResponse.of(result));
  }

  /** Merges one set of settings into every listed table of one database. */
  public void updateSettings(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    checkDatabase(catalog, db);

    OptimizingSettingsInfo info = ctx.bodyAsClass(OptimizingSettingsInfo.class);
    String rejection = validate(info);
    if (rejection != null) {
      badRequest(ctx, rejection);
      return;
    }

    List<ServerTableIdentifier> identifiers = Lists.newArrayList();
    List<String> unknown = Lists.newArrayList();
    for (String tableName : info.getTables()) {
      ServerTableIdentifier identifier =
          StringUtils.isBlank(tableName)
              ? null
              : tableManager.getServerTableIdentifier(
                  TableIdentifier.of(catalog, db, tableName).buildTableIdentifier());
      if (identifier == null) {
        unknown.add(tableName);
      } else {
        identifiers.add(identifier);
      }
    }
    // All or nothing: a partly applied bulk update is harder to reason about than a rejected one.
    if (!unknown.isEmpty()) {
      badRequest(ctx, "Unknown tables in " + catalog + "." + db + ": " + unknown);
      return;
    }

    settingsService.update(identifiers, toMeta(info));
    ctx.json(OkResponse.ok());
  }

  /**
   * Returns the first problem found, or null when the body is acceptable.
   *
   * <p>Cron validation matters more than it looks: {@link CronUtils#hasFiredInWindow} swallows
   * parse failures and returns false, so an unvalidated typo would silently stop that optimizing
   * type instead of reporting anything.
   */
  private static String validate(OptimizingSettingsInfo info) {
    Preconditions.checkArgument(info != null, "Request body is empty!");
    if (info.getTables() == null || info.getTables().isEmpty()) {
      return "tables must list at least one table name";
    }
    if (!CronUtils.isValid(info.getMinorTriggerCron())) {
      return "Invalid minorTriggerCron, expected 5-field unix cron: " + info.getMinorTriggerCron();
    }
    if (!CronUtils.isValid(info.getMajorTriggerCron())) {
      return "Invalid majorTriggerCron, expected 5-field unix cron: " + info.getMajorTriggerCron();
    }
    if (!CronUtils.isValid(info.getFullTriggerCron())) {
      return "Invalid fullTriggerCron, expected 5-field unix cron: " + info.getFullTriggerCron();
    }
    if (info.getTargetSize() != null && info.getTargetSize() <= 0) {
      return "targetSize must be positive, got " + info.getTargetSize();
    }
    return null;
  }

  private static TableOptimizingSettingsMeta toMeta(OptimizingSettingsInfo info) {
    TableOptimizingSettingsMeta meta = new TableOptimizingSettingsMeta();
    meta.setSelfOptimizingEnabled(info.getSelfOptimizingEnabled());
    meta.setMinorTriggerCron(StringUtils.trimToNull(info.getMinorTriggerCron()));
    meta.setMajorTriggerCron(StringUtils.trimToNull(info.getMajorTriggerCron()));
    meta.setFullTriggerCron(StringUtils.trimToNull(info.getFullTriggerCron()));
    meta.setTargetSize(info.getTargetSize());
    return meta;
  }

  private void checkDatabase(String catalog, String db) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog) && StringUtils.isNotBlank(db),
        "catalog.database can not be empty in any element");
    Preconditions.checkState(catalogManager.catalogExist(catalog), "invalid catalog!");
  }

  private static void badRequest(Context ctx, String message) {
    ctx.status(HttpCode.BAD_REQUEST);
    ctx.json(ErrorResponse.of(message));
  }

  /**
   * The optimizing config AMS is currently using, read from the table runtime so it reflects the
   * overlay that was actually applied. Null when the table has no runtime yet.
   */
  private OptimizingConfig effectiveOf(ServerTableIdentifier identifier) {
    TableRuntimeMeta runtimeMeta = tableManager.getTableRuntimeMata(identifier);
    return runtimeMeta == null
        ? null
        : TableConfigurations.parseOptimizingConfig(runtimeMeta.getTableConfig());
  }

  /**
   * One entry of the listing: the overrides stored for a table, and the optimizing config those
   * overrides resolve to once the table properties and the defaults are taken into account.
   */
  public static class TableOptimizingSettings {
    private final String database;
    private final String table;
    private final Boolean selfOptimizingEnabled;
    private final String minorTriggerCron;
    private final String majorTriggerCron;
    private final String fullTriggerCron;
    private final Long targetSize;
    private final OptimizingConfig effective;

    TableOptimizingSettings(
        ServerTableIdentifier identifier,
        TableOptimizingSettingsMeta stored,
        OptimizingConfig effective) {
      this.database = identifier.getDatabase();
      this.table = identifier.getTableName();
      this.selfOptimizingEnabled = stored == null ? null : stored.getSelfOptimizingEnabled();
      this.minorTriggerCron = stored == null ? null : stored.getMinorTriggerCron();
      this.majorTriggerCron = stored == null ? null : stored.getMajorTriggerCron();
      this.fullTriggerCron = stored == null ? null : stored.getFullTriggerCron();
      this.targetSize = stored == null ? null : stored.getTargetSize();
      this.effective = effective;
    }

    public String getDatabase() {
      return database;
    }

    public String getTable() {
      return table;
    }

    public Boolean getSelfOptimizingEnabled() {
      return selfOptimizingEnabled;
    }

    public String getMinorTriggerCron() {
      return minorTriggerCron;
    }

    public String getMajorTriggerCron() {
      return majorTriggerCron;
    }

    public String getFullTriggerCron() {
      return fullTriggerCron;
    }

    public Long getTargetSize() {
      return targetSize;
    }

    public OptimizingConfig getEffective() {
      return effective;
    }
  }
}
