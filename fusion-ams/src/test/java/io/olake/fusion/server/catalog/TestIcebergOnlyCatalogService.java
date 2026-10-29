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

import io.olake.fusion.TableFormat;
import io.olake.fusion.TableIDWithFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.catalog.CatalogTestHelpers;
import io.olake.fusion.hive.TestHMS;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.server.AMSServiceTestBase;
import io.olake.fusion.server.persistence.PersistentBase;
import io.olake.fusion.server.persistence.mapper.CatalogMetaMapper;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Catalogs of a table format other than Iceberg are rejected on create and skipped on load. */
public class TestIcebergOnlyCatalogService extends AMSServiceTestBase {

  @ClassRule public static TestHMS TEST_HMS = new TestHMS();

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private static CatalogMeta hadoopCatalog(String name, String warehouse, TableFormat format) {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(CatalogMetaProperties.KEY_WAREHOUSE, warehouse);
    return CatalogTestHelpers.buildCatalogMeta(
        name, CatalogMetaProperties.CATALOG_TYPE_HADOOP, properties, format);
  }

  @Test
  public void createCatalogWithNonIcebergFormatIsRejectedAndNotPersisted() throws Exception {
    File warehouse = temp.newFolder();
    CatalogMeta meta =
        hadoopCatalog("mixed_rejected", warehouse.getAbsolutePath(), TableFormat.MIXED_ICEBERG);

    Exception e = Assert.assertThrows(Exception.class, () -> CATALOG_MANAGER.createCatalog(meta));

    Assert.assertTrue(
        "message should name the format, was: " + e.getMessage(),
        e.getMessage().contains(TableFormat.MIXED_ICEBERG.name()));
    Assert.assertFalse(CATALOG_MANAGER.catalogExist("mixed_rejected"));
    Assert.assertFalse(
        "connection test must not run for a rejected catalog",
        new File(warehouse, "test_olake/test_olake").exists());
  }

  @Test
  public void storedNonIcebergCatalogIsSkippedWhenCatalogsLoad() throws Exception {
    CatalogMeta iceberg =
        hadoopCatalog("stored_iceberg", temp.newFolder().getAbsolutePath(), TableFormat.ICEBERG);
    CatalogMeta mixed =
        hadoopCatalog(
            "stored_mixed", temp.newFolder().getAbsolutePath(), TableFormat.MIXED_ICEBERG);
    CatalogRows rows = new CatalogRows();
    rows.insert(iceberg);
    rows.insert(mixed);
    try {
      List<String> names =
          CATALOG_MANAGER.getServerCatalogs().stream()
              .map(ServerCatalog::name)
              .collect(Collectors.toList());

      Assert.assertTrue(names.contains("stored_iceberg"));
      Assert.assertFalse(names.contains("stored_mixed"));
    } finally {
      rows.delete("stored_iceberg");
      rows.delete("stored_mixed");
    }
  }

  @Test
  public void hiveMetastoreIcebergCatalogListsTables() throws Exception {
    String warehouse = temp.newFolder().getAbsolutePath();
    HiveConf hiveConf = TEST_HMS.getHiveConf();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(CatalogProperties.URI, hiveConf.get(HiveConf.ConfVars.METASTOREURIS.varname));
    properties.put(CatalogProperties.WAREHOUSE_LOCATION, warehouse);
    CatalogMeta meta =
        CatalogTestHelpers.buildHiveCatalogMeta(
            "hms_iceberg", properties, hiveConf, TableFormat.ICEBERG);

    Map<String, String> icebergProperties = Maps.newHashMap(properties);
    icebergProperties.put(CatalogUtil.ICEBERG_CATALOG_TYPE, CatalogUtil.ICEBERG_CATALOG_TYPE_HIVE);
    Catalog icebergCatalog =
        CatalogUtil.buildIcebergCatalog("hms_iceberg", icebergProperties, hiveConf);
    ((SupportsNamespaces) icebergCatalog).createNamespace(Namespace.of("hms_db"));
    icebergCatalog.createTable(
        TableIdentifier.of("hms_db", "orders"),
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
        PartitionSpec.unpartitioned());

    CATALOG_MANAGER.createCatalog(meta);
    try {
      List<TableIDWithFormat> tables =
          CATALOG_MANAGER.getServerCatalog("hms_iceberg").listTables("hms_db");

      Assert.assertEquals(1, tables.size());
      Assert.assertEquals("orders", tables.get(0).table());
      Assert.assertEquals(TableFormat.ICEBERG, tables.get(0).getTableFormat());
    } finally {
      CATALOG_MANAGER.dropCatalog("hms_iceberg");
    }
  }

  /** Writes catalog rows the way a database from an older version holds them. */
  private static class CatalogRows extends PersistentBase {
    void insert(CatalogMeta meta) {
      doAs(CatalogMetaMapper.class, mapper -> mapper.insertCatalog(meta));
    }

    void delete(String name) {
      doAs(CatalogMetaMapper.class, mapper -> mapper.deleteCatalog(name));
    }
  }
}
