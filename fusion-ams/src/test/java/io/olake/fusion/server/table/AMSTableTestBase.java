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

package io.olake.fusion.server.table;

import io.olake.fusion.FusionTable;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableRuntime;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.formats.iceberg.IcebergCatalog;
import io.olake.fusion.hive.TestHMS;
import io.olake.fusion.server.AMSServiceTestBase;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.List;

public class AMSTableTestBase extends AMSServiceTestBase {
  @ClassRule public static TestHMS TEST_HMS = new TestHMS();

  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final CatalogTestHelper catalogTestHelper;
  private final TableTestHelper tableTestHelper;
  private String catalogWarehouse;
  private IcebergCatalog externalCatalog;
  private CatalogMeta catalogMeta;

  private final boolean autoInitTable;
  private ServerTableIdentifier serverTableIdentifier;

  public AMSTableTestBase(CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    this(catalogTestHelper, tableTestHelper, false);
  }

  public AMSTableTestBase(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper, boolean autoInitTable) {
    this.catalogTestHelper = catalogTestHelper;
    this.tableTestHelper = tableTestHelper;
    this.autoInitTable = autoInitTable;
  }

  @Before
  public void init() throws IOException, TException {
    try {
      catalogWarehouse = temp.newFolder().getPath();
      catalogMeta = catalogTestHelper.buildCatalogMeta(catalogWarehouse);
      externalCatalog = IcebergCatalog.of(catalogMeta);

      CATALOG_MANAGER.createCatalog(catalogMeta);
      try {
        Database database = new Database();
        database.setName(TableTestHelper.TEST_DB_NAME);
        TEST_HMS.getHiveClient().createDatabase(database);
      } catch (AlreadyExistsException e) {
        // pass
      }
      if (autoInitTable) {
        createDatabase();
        createTable();
      }
    } catch (Throwable throwable) {
      Assert.fail(throwable.getMessage());
    }
  }

  @After
  public void dispose() throws TException {
    if (autoInitTable) {
      dropTable();
      dropDatabase();
    }
    if (catalogMeta != null) {
      CATALOG_MANAGER.dropCatalog(catalogMeta.getCatalogName());
      TEST_HMS.getHiveClient().dropDatabase(TableTestHelper.TEST_DB_NAME, false, true);
    }
  }

  protected void createDatabase() {
    List<String> databases = externalCatalog.listDatabases();
    if (!(databases != null && databases.contains(TableTestHelper.TEST_DB_NAME))) {
      externalCatalog.createDatabase(TableTestHelper.TEST_DB_NAME);
    }
  }

  protected void dropDatabase() {
    externalCatalog.dropDatabase(TableTestHelper.TEST_DB_NAME);
  }

  protected void createTable() {
    Preconditions.checkState(
        externalCatalog != null && TableFormat.ICEBERG.equals(catalogTestHelper.tableFormat()),
        "Test tables are Iceberg tables in an external catalog, got %s",
        catalogTestHelper.tableFormat());
    createIcebergTable();
    tableService().exploreTableRuntimes();

    serverTableIdentifier = tableManager().listManagedTables().get(0);
  }

  private void createIcebergTable() {
    org.apache.iceberg.catalog.Catalog catalog = catalogTestHelper.buildIcebergCatalog(catalogMeta);
    catalog.createTable(
        TableIdentifier.of(tableTestHelper.id().getDatabase(), tableTestHelper.id().getTableName()),
        tableTestHelper.tableSchema(),
        tableTestHelper.partitionSpec(),
        tableTestHelper.tableProperties());
  }

  protected void dropTable() {
    String database = tableTestHelper.id().getDatabase();
    String table = tableTestHelper.id().getTableName();
    externalCatalog.dropTable(database, table, true);
    tableService().exploreTableRuntimes();
  }

  protected void dropTableOnly() {
    String database = tableTestHelper.id().getDatabase();
    String table = tableTestHelper.id().getTableName();
    externalCatalog.dropTable(database, table, true);
  }

  protected CatalogTestHelper catalogTestHelper() {
    return catalogTestHelper;
  }

  protected TableTestHelper tableTestHelper() {
    return tableTestHelper;
  }

  protected CatalogMeta catalogMeta() {
    return catalogMeta;
  }

  protected ServerTableIdentifier serverTableIdentifier() {
    return serverTableIdentifier;
  }

  protected void validateTable(FusionTable table) {
    Assert.assertEquals(catalogTestHelper().tableFormat(), table.format());
    Assert.assertEquals(TableTestHelper.TEST_TABLE_ID, table.id());
    Assert.assertEquals(
        tableTestHelper().tableSchema().asStruct(), table.table().schema().asStruct());
    Assert.assertEquals(tableTestHelper().partitionSpec(), table.table().spec());
  }

  protected void validateTableRuntime(TableRuntime tableRuntime) {
    Assert.assertEquals(serverTableIdentifier(), tableRuntime.getTableIdentifier());
  }

  protected void exploreTableRuntimes() {
    tableService().exploreTableRuntimes();
  }
}
