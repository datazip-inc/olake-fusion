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
import org.apache.amoro.utils.CatalogUtil;
import org.apache.amoro.utils.MixedFormatCatalogUtil;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.UnpartitionedWriter;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

public class CatalogConnectionTester {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogConnectionTester.class);

  static final String TEST_NAMESPACE = "test_olake";
  static final String TEST_TABLE = "test_olake";

  private static final Schema TEST_SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "_olake_id", Types.StringType.get()),
          Types.NestedField.optional(2, "_olake_timestamp", Types.TimestampType.withZone()),
          Types.NestedField.optional(3, "_op_type", Types.StringType.get()),
          Types.NestedField.optional(4, "_cdc_timestamp", Types.TimestampType.withZone()),
          Types.NestedField.optional(5, "data", Types.StringType.get()));

  public static void runTestConnection(CatalogMeta catalogMeta) throws Exception {
    String catalogName = catalogMeta.getCatalogName();
    TableMetaStore metaStore = CatalogUtil.buildMetaStore(catalogMeta);
    String metastoreType = catalogMeta.getCatalogType();
    // We cannot use icebergCatalog.java  because it does not support createTable operation.
    // Building raw iceberg catalog that supports createTable operation.
    Map<String, String> baseIcebergProps =
        MixedFormatCatalogUtil.withIcebergCatalogInitializeProperties(
            catalogName, metastoreType, catalogMeta.getCatalogProperties());
    Map<String, String> icebergProps =
        CatalogMetaProperties.CATALOG_TYPE_GLUE.equalsIgnoreCase(metastoreType)
            ? StaticAwsCredentialsProvider.applyGlueCredentials(baseIcebergProps)
            : baseIcebergProps;

    metaStore.doAs(
        () -> {
          Catalog catalog =
              org.apache.iceberg.CatalogUtil.buildIcebergCatalog(
                  catalogName, icebergProps, metaStore.getConfiguration());
          try {
            createNamespaceAndTable(catalog, catalogName);
          } finally {
            closeQuietly(catalog);
          }
          return null;
        });
  }

  private static void createNamespaceAndTable(Catalog catalog, String catalogName)
      throws Exception {
    SupportsNamespaces nsCatalog = (SupportsNamespaces) catalog;
    Namespace ns = Namespace.of(TEST_NAMESPACE);
    TableIdentifier tableId = TableIdentifier.of(ns, TEST_TABLE);
    // Create namespace if it does not already exist.
    if (!nsCatalog.namespaceExists(ns)) {
      try {
        nsCatalog.createNamespace(ns);
        LOG.info("Connection test namespace {} created", TEST_NAMESPACE);
      } catch (Exception e) {
        LOG.error(
            "Connection test failed while creating namespace {}: {}",
            TEST_NAMESPACE,
            e.getMessage(),
            e);
        throw e;
      }
    } else {
      LOG.info("Connection test namespace {} already exists, reusing it", TEST_NAMESPACE);
    }

    // Create table if it does not already exist, otherwise load the existing one.
    if (!catalog.tableExists(tableId)) {
      try {
        catalog.createTable(tableId, TEST_SCHEMA, PartitionSpec.unpartitioned());
        LOG.info("Connection test table {} created", tableId);
      } catch (Exception e) {
        LOG.error("Connection test failed while creating table {}: {}", tableId, e.getMessage(), e);
        throw e;
      }
    } else {
      LOG.info("Connection test table {} already exists, reusing it", tableId);
    }
    try {
      writeTestRecord(catalog, tableId);
    } catch (Exception e) {
      LOG.error(
          "Connection test failed while writing test record to {}: {}", tableId, e.getMessage(), e);
      throw e;
    }
    LOG.info("Test connection finished successfully for catalog {}", catalogName);
  }

  private static void writeTestRecord(Catalog catalog, TableIdentifier tableId) throws Exception {
    Table table = catalog.loadTable(tableId);
    appendRecord(table, createTestRecord(table.schema()));
    LOG.info("Test record written successfully to table {}", tableId);
  }

  private static GenericRecord createTestRecord(Schema schema) {
    GenericRecord record = GenericRecord.create(schema);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    record.setField("_olake_id", "olake");
    record.setField("_olake_timestamp", now);
    record.setField("_op_type", "r");
    record.setField("_cdc_timestamp", now);
    record.setField("data", "{\"name\":\"olake\"}");
    return record;
  }

  private static void appendRecord(Table table, Record record) throws IOException {
    Schema schema = table.schema();
    WriteResult result;
    try (UnpartitionedWriter<Record> writer =
        new UnpartitionedWriter<>(
            table.spec(),
            FileFormat.PARQUET,
            new GenericAppenderFactory(schema, table.spec()),
            OutputFileFactory.builderFor(table, 0, 0).build(),
            table.io(),
            Long.MAX_VALUE)) {
      writer.write(record);
      result = writer.complete();
    }
    AppendFiles append = table.newFastAppend();
    for (DataFile dataFile : result.dataFiles()) {
      append.appendFile(dataFile);
    }
    append.commit();
  }

  private static void closeQuietly(Catalog catalog) {
    if (catalog instanceof AutoCloseable) {
      try {
        ((AutoCloseable) catalog).close();
      } catch (Exception e) {
        LOG.warn("Failed to close catalog {}", catalog.name(), e);
      }
    }
  }
}
