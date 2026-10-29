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

package io.olake.fusion.server;

import io.olake.fusion.config.Configurations;
import io.olake.fusion.resource.ResourceGroup;
import io.olake.fusion.server.catalog.DefaultCatalogManager;
import io.olake.fusion.server.manager.EventsManager;
import io.olake.fusion.server.manager.MetricManager;
import io.olake.fusion.server.resource.DefaultOptimizerManager;
import io.olake.fusion.server.resource.OptimizerManager;
import io.olake.fusion.server.table.DefaultTableManager;
import io.olake.fusion.server.table.DerbyPersistence;
import io.olake.fusion.server.table.TableManager;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;

public abstract class AMSManagerTestBase {

  @ClassRule public static DerbyPersistence DERBY = new DerbyPersistence();

  protected static DefaultCatalogManager CATALOG_MANAGER = null;
  protected static DefaultTableManager TABLE_MANAGER = null;
  protected static DefaultOptimizerManager OPTIMIZER_MANAGER = null;

  @BeforeClass
  public static void initTableManger() {
    try {
      Configurations configurations = new Configurations();
      CATALOG_MANAGER = new DefaultCatalogManager(configurations);
      TABLE_MANAGER = new DefaultTableManager(configurations, CATALOG_MANAGER);
      OPTIMIZER_MANAGER = new DefaultOptimizerManager(configurations, CATALOG_MANAGER);
    } catch (Throwable throwable) {
      Assert.fail(throwable.getMessage());
    }
  }

  @AfterClass
  public static void disposeTableService() {
    MetricManager.dispose();
    EventsManager.dispose();
  }

  protected DefaultCatalogManager catalogManager() {
    return CATALOG_MANAGER;
  }

  protected TableManager tableManager() {
    return TABLE_MANAGER;
  }

  protected OptimizerManager optimizerManager() {
    return OPTIMIZER_MANAGER;
  }

  protected static ResourceGroup defaultResourceGroup() {
    return new ResourceGroup.Builder("default", "local").build();
  }
}
