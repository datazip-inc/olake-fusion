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

package io.olake.fusion.formats;

import io.olake.fusion.FusionCatalog;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

public abstract class FusionCatalogTestBase {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  protected FusionCatalogTestHelper<?> catalogTestHelper;

  protected FusionCatalog amoroCatalog;

  protected Object originalCatalog;

  public FusionCatalogTestBase(FusionCatalogTestHelper<?> catalogTestHelper) {
    this.catalogTestHelper = catalogTestHelper;
  }

  @Before
  public void setupCatalog() throws IOException {
    String path = temp.newFolder().getPath();
    catalogTestHelper.initWarehouse(path);
    this.amoroCatalog = catalogTestHelper.amoroCatalog();
    this.originalCatalog = catalogTestHelper.originalCatalog();
  }

  @After
  public void cleanCatalog() {
    catalogTestHelper.clean();
  }
}
