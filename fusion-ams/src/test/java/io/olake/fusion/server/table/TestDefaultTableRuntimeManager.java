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

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.exception.ObjectNotExistsException;
import io.olake.fusion.table.MixedTable;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestDefaultTableRuntimeManager extends AMSTableTestBase {

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)}
    };
  }

  public TestDefaultTableRuntimeManager(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper, true);
  }

  @Test
  public void testLoadTable() {
    MixedTable mixedTable =
        (MixedTable) tableService().loadTable(serverTableIdentifier()).originalTable();
    validateMixedTable(mixedTable);

    // test load not existed table
    Assert.assertThrows(
        ObjectNotExistsException.class,
        () ->
            tableService()
                .loadTable(
                    ServerTableIdentifier.of(
                        "unknown", "unknown", "unknown", serverTableIdentifier().getFormat())));
  }

  @Test
  public void testTableRuntime() {
    DefaultTableRuntime tableRuntime = getDefaultTableRuntime(serverTableIdentifier().getId());
    validateTableRuntime(tableRuntime);
  }
}
