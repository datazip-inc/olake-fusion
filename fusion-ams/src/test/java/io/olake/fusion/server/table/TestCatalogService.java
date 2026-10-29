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

import static io.olake.fusion.properties.CatalogMetaProperties.CLIENT_POOL_SIZE;

import io.olake.fusion.TableFormat;
import io.olake.fusion.TestedCatalogs;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.exception.AlreadyExistsException;
import io.olake.fusion.exception.IllegalMetadataException;
import io.olake.fusion.exception.ObjectNotExistsException;
import io.olake.fusion.hive.TestHMS;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.server.AMSServiceTestBase;
import io.olake.fusion.server.catalog.InternalCatalog;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.List;

@RunWith(Parameterized.class)
public class TestCatalogService extends AMSServiceTestBase {
  @ClassRule public static TestHMS TEST_HMS = new TestHMS();

  private final CatalogTestHelper catalogTestHelper;

  @Parameterized.Parameters(name = "{0}")
  public static Object[] parameters() {
    return new Object[][] {{TestedCatalogs.internalCatalog(TableFormat.ICEBERG)}};
  }

  public TestCatalogService(CatalogTestHelper catalogTestHelper) {
    this.catalogTestHelper = catalogTestHelper;
  }

  @Test
  public void testCreateAndDropCatalog() {
    CatalogMeta catalogMeta = catalogTestHelper.buildCatalogMeta("/tmp");
    // test create catalog
    CATALOG_MANAGER.createCatalog(catalogMeta);

    // test create duplicate catalog
    Assert.assertThrows(
        AlreadyExistsException.class, () -> CATALOG_MANAGER.createCatalog(catalogMeta));

    // test get catalog
    CatalogMeta readCatalogMeta = CATALOG_MANAGER.getCatalogMeta(catalogMeta.getCatalogName());
    Assert.assertEquals(catalogMeta, readCatalogMeta);

    // test get catalog list
    List<CatalogMeta> catalogMetas = CATALOG_MANAGER.listCatalogMetas();
    Assert.assertEquals(1, catalogMetas.size());
    Assert.assertEquals(
        catalogMeta,
        catalogMetas.stream()
            .filter(meta -> meta.getCatalogName().equals(catalogMeta.getCatalogName()))
            .findAny()
            .orElseThrow(() -> new IllegalStateException("Cannot find expect catalog")));

    // test catalogExist
    Assert.assertTrue(CATALOG_MANAGER.catalogExist(catalogMeta.getCatalogName()));

    // test drop catalog
    CATALOG_MANAGER.dropCatalog(catalogMeta.getCatalogName());

    // test drop not existed catalog
    Assert.assertThrows(
        ObjectNotExistsException.class,
        () -> CATALOG_MANAGER.getCatalogMeta(catalogMeta.getCatalogName()));

    Assert.assertFalse(CATALOG_MANAGER.catalogExist(catalogMeta.getCatalogName()));
  }

  @Test
  public void testUpdateCatalog() {
    CatalogMeta catalogMeta = catalogTestHelper.buildCatalogMeta("/tmp");
    CATALOG_MANAGER.createCatalog(catalogMeta);

    CatalogMeta updateCatalogMeta = new CatalogMeta(catalogMeta);
    updateCatalogMeta.getCatalogProperties().put("k2", "V2");
    updateCatalogMeta.getCatalogProperties().put("k3", "v3");
    CATALOG_MANAGER.updateCatalog(updateCatalogMeta);
    CatalogMeta getCatalogMeta = CATALOG_MANAGER.getCatalogMeta(catalogMeta.getCatalogName());
    Assert.assertEquals("V2", getCatalogMeta.getCatalogProperties().get("k2"));
    Assert.assertEquals("v3", getCatalogMeta.getCatalogProperties().get("k3"));
    Assert.assertEquals(
        updateCatalogMeta, CATALOG_MANAGER.getCatalogMeta(catalogMeta.getCatalogName()));

    // test update catalog type
    final CatalogMeta updateCatalogMeta2 = new CatalogMeta(updateCatalogMeta);
    updateCatalogMeta2.setCatalogType(CatalogMetaProperties.CATALOG_TYPE_CUSTOM);
    Assert.assertThrows(
        IllegalMetadataException.class, () -> CATALOG_MANAGER.updateCatalog(updateCatalogMeta2));

    // test update unknown catalog
    CATALOG_MANAGER.dropCatalog(catalogMeta.getCatalogName());
    Assert.assertThrows(
        ObjectNotExistsException.class, () -> CATALOG_MANAGER.updateCatalog(catalogMeta));
  }

  @Test
  public void testDropCatalogWithDatabase() {
    Assume.assumeTrue(catalogTestHelper.tableFormat().equals(TableFormat.MIXED_ICEBERG));
    CatalogMeta catalogMeta = catalogTestHelper.buildCatalogMeta("/tmp");
    CATALOG_MANAGER.createCatalog(catalogMeta);
    InternalCatalog catalog = CATALOG_MANAGER.getInternalCatalog(catalogMeta.getCatalogName());
    catalog.createDatabase("test_db");
    Assert.assertThrows(
        IllegalMetadataException.class,
        () -> CATALOG_MANAGER.dropCatalog(catalogMeta.getCatalogName()));
    catalog.dropDatabase("test_db");
    CATALOG_MANAGER.dropCatalog(catalogMeta.getCatalogName());
  }

  @Test
  public void testCreateCatalogWithProperties() {
    CatalogMeta catalogMeta = catalogTestHelper.buildCatalogMeta("/tmp");
    catalogMeta.getCatalogProperties().put(CLIENT_POOL_SIZE, "15");
    CATALOG_MANAGER.createCatalog(catalogMeta);
    CatalogMeta readCatalogMeta = CATALOG_MANAGER.getCatalogMeta(catalogMeta.getCatalogName());
    Assert.assertEquals("15", readCatalogMeta.getCatalogProperties().get(CLIENT_POOL_SIZE));
    CATALOG_MANAGER.dropCatalog(catalogMeta.getCatalogName());
  }
}
