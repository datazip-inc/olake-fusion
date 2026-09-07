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
import org.apache.amoro.server.catalog.CatalogManager;
import org.apache.amoro.server.dashboard.model.OptimizingConfigurations;
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.amoro.server.table.TableConfigurationsService;
import org.apache.amoro.server.table.TableManager;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.table.TableIdentifier;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Reads and writes the table configuratison in AMS db.
 *
 * <p>Only the configuration keys provided are updated, the rest are left intact.
 */
public class TableConfigurationDetails {
  private final CatalogManager catalogManager;
  private final TableManager tableManager;
  private final TableConfigurationsService configurations;

  public TableConfigurationDetails(CatalogManager catalogManager, TableManager tableManager) {
    this.catalogManager = catalogManager;
    this.tableManager = tableManager;
    this.configurations = TableConfigurationsService.getInstance();
  }

  public void getIcebergTables(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    check(catalog, db);

    List<TableOptimizingConfigurationsMeta> result = Lists.newArrayList();
    for (ServerTableIdentifier identifier : tableManager.listIcebergTables(catalog, db)) {
      result.add(configurations.getOrCreate(identifier));
    }
    ctx.json(OkResponse.of(result));
  }

  public void getTableConfig(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    String table = ctx.pathParam("table");
    check(catalog, db);
    Preconditions.checkArgument(StringUtils.isNotBlank(table), "table can not be empty");

    ServerTableIdentifier identifier =
        tableManager.getServerTableIdentifier(
            TableIdentifier.of(catalog, db, table).buildTableIdentifier());
    Preconditions.checkArgument(
        identifier != null, "Unknown table %s.%s.%s", catalog, db, table);
    Preconditions.checkArgument(
        TableFormat.ICEBERG.equals(identifier.getFormat()),
        "%s.%s.%s is not an iceberg table",
        catalog,
        db,
        table);

    ctx.json(OkResponse.of(configurations.getOrCreate(identifier)));
  }

  public void updateConfigurations(Context ctx) {
    String catalog = ctx.pathParam("catalog");
    String db = ctx.pathParam("db");
    check(catalog, db);

    OptimizingConfigurations info = ctx.bodyAsClass(OptimizingConfigurations.class);

    List<ServerTableIdentifier> identifiers = Lists.newArrayList();
    for (String tableName : info.getTables()) {
      ServerTableIdentifier identifier =
          tableManager.getServerTableIdentifier(
              TableIdentifier.of(catalog, db, tableName).buildTableIdentifier());
      if (identifier != null) {
        identifiers.add(identifier);
      }
    }

    configurations.update(identifiers, info);
    ctx.json(OkResponse.ok());
  }

  private void check(String catalog, String db) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(catalog) && StringUtils.isNotBlank(db),
        "catalog.database can not be empty in any element");
    Preconditions.checkState(catalogManager.catalogExist(catalog), "invalid catalog!");
  }

}
