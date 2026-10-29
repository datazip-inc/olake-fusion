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

import io.olake.fusion.FusionCatalog;
import io.olake.fusion.formats.FusionCatalogTestHelper;
import io.olake.fusion.hive.TestHMS;
import io.olake.fusion.server.AMSServiceTestBase;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

public class TableCatalogTestBase extends AMSServiceTestBase {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @ClassRule public static TestHMS TEST_HMS = new TestHMS();

  private final FusionCatalogTestHelper<?> amoroCatalogTestHelper;

  private FusionCatalog amoroCatalog;

  private Object originalCatalog;

  public TableCatalogTestBase(FusionCatalogTestHelper<?> amoroCatalogTestHelper) {
    this.amoroCatalogTestHelper = amoroCatalogTestHelper;
  }

  @Before
  public void init() throws IOException {
    String path = temp.newFolder().getPath();
    amoroCatalogTestHelper.initWarehouse(path);
    amoroCatalogTestHelper.initHiveConf(TEST_HMS.getHiveConf());
    this.amoroCatalog = amoroCatalogTestHelper.amoroCatalog();
    CATALOG_MANAGER.createCatalog(amoroCatalogTestHelper.getCatalogMeta());
    this.originalCatalog = amoroCatalogTestHelper.originalCatalog();
  }

  @After
  public void clean() {
    CATALOG_MANAGER.dropCatalog(amoroCatalogTestHelper.catalogName());
    amoroCatalogTestHelper.clean();
  }

  public FusionCatalog getAmoroCatalog() {
    return amoroCatalog;
  }

  public Object getOriginalCatalog() {
    return originalCatalog;
  }

  public FusionCatalogTestHelper<?> getAmoroCatalogTestHelper() {
    return amoroCatalogTestHelper;
  }
}
