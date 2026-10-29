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

import static io.olake.fusion.TableTestHelper.TEST_DB_NAME;
import static io.olake.fusion.catalog.CatalogTestHelper.TEST_CATALOG_NAME;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.TestedCatalogs;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.exception.AlreadyExistsException;
import io.olake.fusion.exception.ObjectNotExistsException;
import io.olake.fusion.server.catalog.InternalCatalog;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestDatabaseService extends AMSTableTestBase {

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {TestedCatalogs.internalCatalog(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)}
    };
  }

  public TestDatabaseService(CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  @Test
  public void testCreateAndDropDatabase() {
    InternalCatalog catalog = CATALOG_MANAGER.getInternalCatalog(TEST_CATALOG_NAME);
    // test create database
    catalog.createDatabase(TEST_DB_NAME);

    // test create duplicate database
    Assert.assertThrows(AlreadyExistsException.class, () -> catalog.createDatabase(TEST_DB_NAME));

    // test list database
    Assert.assertEquals(Lists.newArrayList(TEST_DB_NAME), catalog.listDatabases());

    // test drop database
    catalog.dropDatabase(TEST_DB_NAME);
    Assert.assertEquals(Lists.newArrayList(), catalog.listDatabases());

    // test drop unknown database
    Assert.assertThrows(ObjectNotExistsException.class, () -> catalog.dropDatabase(TEST_DB_NAME));
  }
}
