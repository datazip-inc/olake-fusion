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

package io.olake.fusion.server.optimizing.plan;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.optimizing.plan.AbstractPartitionPlan;
import io.olake.fusion.optimizing.scan.IcebergTableFileScanHelper;
import io.olake.fusion.optimizing.scan.TableFileScanHelper;
import io.olake.fusion.server.utils.IcebergTableUtil;
import org.apache.iceberg.Table;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Map;

@RunWith(Parameterized.class)
public abstract class TestUnkeyedPartitionPlan extends MixedTablePlanTestBase {

  public TestUnkeyedPartitionPlan(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[][] parameters() {
    return new Object[][] {
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)},
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, false)}
    };
  }

  @Test
  public void testFragmentFiles() {
    testFragmentFilesBase();
  }

  @Test
  public void testSegmentFiles() {
    testSegmentFilesBase();
  }

  @Test
  public void testWithDeleteFiles() {
    testWithDeleteFilesBase();
  }

  @Test
  public void testOnlyOneFragmentFiles() {
    testOnlyOneFragmentFileBase();
  }

  @Override
  protected abstract AbstractPartitionPlan getPartitionPlan();

  @Override
  protected TableFileScanHelper getTableFileScanHelper() {
    long baseSnapshotId = IcebergTableUtil.getSnapshotId(getTable(), true);
    return new IcebergTableFileScanHelper(getTable(), baseSnapshotId);
  }

  @Override
  protected Table getTable() {
    return super.getTable();
  }

  @Override
  protected abstract Map<String, String> buildTaskProperties();
}
