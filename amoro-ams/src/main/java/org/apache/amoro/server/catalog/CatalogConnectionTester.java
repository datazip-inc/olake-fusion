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

package org.apache.amoro.server.catalog;

import org.apache.amoro.api.CatalogMeta;
import org.apache.amoro.aws.StaticAwsCredentialsProvider;
import org.apache.amoro.properties.CatalogMetaProperties;
import org.apache.amoro.table.TableMetaStore;
import org.apache.amoro.utils.MixedFormatCatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class CatalogConnectionTester {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogConnectionTester.class);

  static final String TEST_NAMESPACE = "test_olake";
  static final String TEST_TABLE = "test_olake";

  private static final Schema TEST_SCHEMA =
      new Schema(
          Types.NestedField.required(1, "_olake_id", Types.StringType.get()),
          Types.NestedField.required(2, "_olake_timestamp", Types.TimestampType.withZone()),
          Types.NestedField.required(3, "_op_type", Types.StringType.get()),
          Types.NestedField.optional(4, "_cdc_timestamp", Types.TimestampType.withZone()),
          Types.NestedField.required(5, "data", Types.StringType.get()));

  public static void runTestConnection(ServerCatalog serverCatalog) throws Exception {
    CatalogMeta catalogMeta = serverCatalog.getMetadata();
    String catalogName = catalogMeta.getCatalogName();
    String metastoreType = catalogMeta.getCatalogType();
    TableMetaStore metaStore = serverCatalog.metaStore;

    // We cannot use icebergCatalog.java  because it does not support createTable operation.
    // Building raw iceberg catalog that supports createTable operation.

    // create iceberg catalog properties
    Map<String, String> baseIcebergProps =
        MixedFormatCatalogUtil.withIcebergCatalogInitializeProperties(
            catalogName, metastoreType, catalogMeta.getCatalogProperties());
    Map<String, String> icebergProps =
        CatalogMetaProperties.CATALOG_TYPE_GLUE.equalsIgnoreCase(metastoreType)
            ? StaticAwsCredentialsProvider.applyGlueCredentials(baseIcebergProps)
            : baseIcebergProps;

    // create iceberg catalog
    Catalog catalog =
        org.apache.iceberg.CatalogUtil.buildIcebergCatalog(
            catalogName, icebergProps, metaStore.getConfiguration());
    createNamespaceAndTable(catalog, catalogName);
  }

  private static void createNamespaceAndTable(Catalog catalog, String catalogName)
      throws Exception {
    Boolean tableExists = false;
    Boolean namespaceExists = false;
    SupportsNamespaces nsCatalog = (SupportsNamespaces) catalog;
    Namespace ns = Namespace.of(TEST_NAMESPACE);
    TableIdentifier tableId = TableIdentifier.of(ns, TEST_TABLE);
    try {
      try {
        nsCatalog.createNamespace(ns);
        namespaceExists = true;
      } catch (Exception e) {
        LOG.error(
            "Connection test failed while creating namespace {}: {}",
            TEST_NAMESPACE,
            e.getMessage(),
            e);
        throw e;
      }
      try {
        catalog.createTable(tableId, TEST_SCHEMA, PartitionSpec.unpartitioned());
        tableExists = true;
        LOG.info("Test table {} created successfully", tableId);
      } catch (Exception e) {
        LOG.error(
            "Test connection failed  while creating table {}: {}", tableId, e.getMessage(), e);
        throw e;
      }
    } finally {
      if (tableExists || namespaceExists) {
        cleanup(catalog, nsCatalog, ns, tableId, tableExists, namespaceExists);
      }
    }
    LOG.info("Test connection finished successfully for catalog {}", catalogName);
  }

  private static void cleanup(
      Catalog catalog,
      SupportsNamespaces nsCatalog,
      Namespace ns,
      TableIdentifier tableId,
      Boolean tableExists,
      Boolean namespaceExists) {
    try {
      if (tableExists) {
        catalog.dropTable(tableId, true);
        LOG.info("Connection test table {} dropped", tableId);
      }
    } catch (Exception e) {
      LOG.warn("Connection test dropTable {} failed: {}", tableId, e.getMessage(), e);
    }
    try {
      if (namespaceExists) {
        nsCatalog.dropNamespace(ns);
        LOG.info("Connection test namespace {} dropped", ns);
      }
    } catch (Exception e) {
      LOG.warn("Connection test dropNamespace {} failed: {}", ns, e.getMessage(), e);
    }
  }
}
