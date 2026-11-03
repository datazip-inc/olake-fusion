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

package io.olake.fusion.formats.iceberg;

import io.olake.fusion.FusionTable;
import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.aws.StaticAwsCredentialsProvider;
import io.olake.fusion.io.AuthenticatedFileIOs;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.table.TableMetaStore;
import io.olake.fusion.utils.CatalogUtil;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Fusion catalog: the Iceberg {@link Catalog} built from a catalog's metastore type and
 * properties, with the catalog's {@link TableMetaStore} for authentication. Tables are loaded as
 * {@link FusionTable}s.
 */
public class IcebergCatalog {

  private final String name;
  private final String metastoreType;
  private final Map<String, String> properties;
  private final TableMetaStore metaStore;
  private final Catalog icebergCatalog;
  private final SupportsNamespaces asNamespaceCatalog;

  /** Builds the catalog described by a catalog meta. */
  public static IcebergCatalog of(CatalogMeta meta) {
    CatalogUtil.mergeCatalogProperties(meta, null);
    return new IcebergCatalog(
        meta.getCatalogName(),
        meta.getCatalogType(),
        meta.getCatalogProperties(),
        CatalogUtil.buildMetaStore(meta));
  }

  /** Returns true if a catalog with these properties serves Iceberg tables. */
  public static boolean servesIceberg(String metastoreType, Map<String, String> catalogProperties) {
    return CatalogUtil.tableFormats(metastoreType, catalogProperties).contains(TableFormat.ICEBERG);
  }

  /**
   * Builds the Iceberg catalog.
   *
   * @param name catalog name
   * @param metastoreType Fusion catalog type, such as {@code hive}, {@code glue}, {@code rest}, or
   *     {@code custom}
   * @param catalogProperties catalog properties, including the {@code table.*} table defaults
   * @param metaStore authentication context of the catalog
   */
  public IcebergCatalog(
      String name,
      String metastoreType,
      Map<String, String> catalogProperties,
      TableMetaStore metaStore) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metastoreType), "metastore type is blank");
    Map<String, String> icebergProperties =
        CatalogUtil.withIcebergCatalogInitializeProperties(name, metastoreType, catalogProperties);
    // apply glue credentials to glue catalog
    if (CatalogMetaProperties.CATALOG_TYPE_GLUE.equalsIgnoreCase(metastoreType)) {
      icebergProperties = StaticAwsCredentialsProvider.applyGlueCredentials(icebergProperties);
    }
    Catalog catalog =
        org.apache.iceberg.CatalogUtil.buildIcebergCatalog(
            name, icebergProperties, metaStore.getConfiguration());
    this.name = name;
    this.metastoreType = metastoreType;
    this.properties = icebergProperties;
    this.metaStore = metaStore;
    this.icebergCatalog = CatalogUtil.buildCacheCatalog(catalog, icebergProperties);
    this.asNamespaceCatalog =
        catalog instanceof SupportsNamespaces ? (SupportsNamespaces) catalog : null;
  }

  public String name() {
    return name;
  }

  public String metastoreType() {
    return metastoreType;
  }

  public TableMetaStore authenticationContext() {
    return metaStore;
  }

  /** Returns the catalog properties the Iceberg catalog was built with. */
  public Map<String, String> properties() {
    return properties;
  }

  public List<String> listDatabases() {
    return metaStore.doAs(
        () ->
            asNamespaceCatalog().listNamespaces().stream()
                .map(ns -> ns.level(0))
                .collect(Collectors.toList()));
  }

  public boolean databaseExists(String database) {
    return listDatabases().contains(database);
  }

  public boolean tableExists(String database, String table) {
    TableIdentifier identifier = TableIdentifier.of(database, table);
    return metaStore.doAs(() -> icebergCatalog.tableExists(identifier));
  }

  public void createDatabase(String database) {
    metaStore.doAs(
        () -> {
          asNamespaceCatalog().createNamespace(Namespace.of(database));
          return null;
        });
  }

  public void dropDatabase(String database) {
    if (!listTables(database).isEmpty()) {
      throw new IllegalStateException("Database: " + database + " is not empty.");
    }
    metaStore.doAs(
        () -> {
          asNamespaceCatalog().dropNamespace(Namespace.of(database));
          return null;
        });
  }

  /** Returns the names of the tables in the database. */
  public List<String> listTables(String database) {
    return metaStore.doAs(
        () ->
            icebergCatalog.listTables(Namespace.of(database)).stream()
                .map(TableIdentifier::name)
                .collect(Collectors.toList()));
  }

  /**
   * Loads a table.
   *
   * @throws io.olake.fusion.NoSuchTableException if the table does not exist
   */
  public FusionTable loadTable(String database, String table) {
    return metaStore.doAs(
        () -> {
          try {
            Table icebergTable = icebergCatalog.loadTable(TableIdentifier.of(database, table));
            return new FusionTable(
                io.olake.fusion.table.TableIdentifier.of(name, database, table),
                icebergTable,
                AuthenticatedFileIOs.buildAdaptIcebergFileIO(icebergTable.io()),
                properties);
          } catch (NoSuchTableException e) {
            throw new io.olake.fusion.NoSuchTableException(e);
          }
        });
  }

  public boolean dropTable(String database, String table, boolean purge) {
    return metaStore.doAs(
        () -> icebergCatalog.dropTable(TableIdentifier.of(database, table), purge));
  }

  private SupportsNamespaces asNamespaceCatalog() {
    if (asNamespaceCatalog == null) {
      throw new UnsupportedOperationException(
          String.format(
              "Iceberg catalog: %s doesn't implement SupportsNamespaces",
              icebergCatalog.getClass().getName()));
    }
    return asNamespaceCatalog;
  }
}
