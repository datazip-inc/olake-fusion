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

package io.olake.fusion.server.catalog;

import io.olake.fusion.FusionTable;
import io.olake.fusion.NoSuchTableException;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableIDWithFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.formats.iceberg.IcebergCatalog;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.server.persistence.mapper.TableMetaMapper;
import io.olake.fusion.table.TableIdentifier;
import io.olake.fusion.utils.CatalogUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ExternalCatalog extends ServerCatalog {

  /** Null when the catalog does not list the Iceberg table format: it then has no tables. */
  private volatile IcebergCatalog icebergCatalog;

  private Pattern tableFilterPattern;
  private Pattern databaseFilterPattern;

  protected ExternalCatalog(CatalogMeta metadata) {
    super(metadata);
    catalogMetadataChanged();
  }

  @Override
  protected void catalogMetadataChanged() {
    CatalogMeta meta = getMetadata();
    CatalogUtil.mergeCatalogProperties(meta, null);
    this.icebergCatalog =
        IcebergCatalog.servesIceberg(meta.getCatalogType(), meta.getCatalogProperties())
            ? metaStore.doAs(
                () ->
                    new IcebergCatalog(
                        meta.getCatalogName(),
                        meta.getCatalogType(),
                        meta.getCatalogProperties(),
                        metaStore))
            : null;
    updateDatabaseFilter(meta);
    updateTableFilter(meta);
  }

  private IcebergCatalog icebergCatalog() {
    IcebergCatalog catalog = icebergCatalog;
    if (catalog == null) {
      throw new IllegalStateException("No format catalog found.");
    }
    return catalog;
  }

  public void syncTable(String database, String tableName, TableFormat format) {
    ServerTableIdentifier tableIdentifier =
        ServerTableIdentifier.of(getMetadata().getCatalogName(), database, tableName, format);
    doAs(TableMetaMapper.class, mapper -> mapper.insertTable(tableIdentifier));
  }

  public ServerTableIdentifier getServerTableIdentifier(String database, String tableName) {
    return getAs(
        TableMetaMapper.class,
        mapper ->
            mapper.selectTableIdentifier(getMetadata().getCatalogName(), database, tableName));
  }

  public void disposeTable(String database, String tableName) {
    doAs(
        TableMetaMapper.class,
        mapper -> mapper.deleteTableIdByName(getMetadata().getCatalogName(), database, tableName));
  }

  @Override
  public boolean databaseExists(String database) {
    return doAs(() -> icebergCatalog().databaseExists(database));
  }

  @Override
  public boolean tableExists(String database, String tableName) {
    return doAs(() -> icebergCatalog != null && icebergCatalog.tableExists(database, tableName));
  }

  @Override
  public List<String> listDatabases() {
    return doAs(
        () ->
            icebergCatalog().listDatabases().stream()
                .filter(
                    database ->
                        databaseFilterPattern == null
                            || databaseFilterPattern.matcher(database).matches())
                .collect(Collectors.toList()));
  }

  @Override
  public List<TableIDWithFormat> listTables() {
    return doAs(
        () ->
            icebergCatalog().listDatabases().stream()
                .map(this::listTables)
                .flatMap(List::stream)
                .collect(Collectors.toList()));
  }

  @Override
  public List<TableIDWithFormat> listTables(String database) {
    return doAs(
        () ->
            new ArrayList<>(
                icebergTables(database).stream()
                    .filter(
                        tableIDWithFormat ->
                            tableFilterPattern == null
                                || tableFilterPattern
                                    .matcher(
                                        (database
                                            + "."
                                            + tableIDWithFormat.getIdentifier().getTableName()))
                                    .matches())
                    .collect(Collectors.toList())));
  }

  @Override
  public FusionTable loadTable(String database, String tableName) {
    IcebergCatalog catalog = icebergCatalog;
    if (catalog == null) {
      throw new NoSuchTableException("Table: " + tableName + " does not exist.");
    }
    try {
      return doAs(() -> catalog.loadTable(database, tableName));
    } catch (NoSuchTableException e) {
      throw new NoSuchTableException("Table: " + tableName + " does not exist.");
    }
  }

  private List<TableIDWithFormat> icebergTables(String database) {
    IcebergCatalog catalog = icebergCatalog;
    if (catalog == null) {
      return Collections.emptyList();
    }
    return catalog.listTables(database).stream()
        .map(
            table ->
                TableIDWithFormat.of(
                    TableIdentifier.of(catalog.name(), database, table), TableFormat.ICEBERG))
        .collect(Collectors.toList());
  }

  private void updateDatabaseFilter(CatalogMeta metadata) {
    String databaseFilter =
        metadata.getCatalogProperties().get(CatalogMetaProperties.KEY_DATABASE_FILTER);
    if (databaseFilter != null) {
      databaseFilterPattern = Pattern.compile(databaseFilter);
    } else {
      databaseFilterPattern = null;
    }
  }

  private void updateTableFilter(CatalogMeta metadata) {
    String tableFilter =
        metadata.getCatalogProperties().get(CatalogMetaProperties.KEY_TABLE_FILTER);
    if (tableFilter != null) {
      tableFilterPattern = Pattern.compile(tableFilter);
    } else {
      tableFilterPattern = null;
    }
  }

  private <T> T doAs(Callable<T> callable) {
    return metaStore.doAs(callable);
  }
}
