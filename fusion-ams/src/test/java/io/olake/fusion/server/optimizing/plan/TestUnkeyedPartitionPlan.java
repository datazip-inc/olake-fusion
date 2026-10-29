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
import io.olake.fusion.optimizing.MixedIcebergRewriteExecutorFactory;
import io.olake.fusion.optimizing.TaskProperties;
import io.olake.fusion.optimizing.plan.AbstractPartitionPlan;
import io.olake.fusion.optimizing.plan.MixedIcebergPartitionPlan;
import io.olake.fusion.optimizing.scan.TableFileScanHelper;
import io.olake.fusion.optimizing.scan.UnkeyedTableFileScanHelper;
import io.olake.fusion.server.utils.IcebergTableUtil;
import io.olake.fusion.table.UnkeyedTable;
import org.apache.amoro.shade.guava32.com.google.common.collect.Maps;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Map;

@RunWith(Parameterized.class)
public class TestUnkeyedPartitionPlan extends MixedTablePlanTestBase {

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
  protected AbstractPartitionPlan getPartitionPlan() {
    return new MixedIcebergPartitionPlan(
        getTableRuntime().getTableIdentifier(),
        getMixedTable(),
        getTableRuntime().getOptimizingConfig(),
        getPartition(),
        System.currentTimeMillis(),
        getTableRuntime().getLastMinorOptimizingTime(),
        getTableRuntime().getLastFullOptimizingTime(),
        getTableRuntime().getLastMajorOptimizingTime());
  }

  @Override
  protected TableFileScanHelper getTableFileScanHelper() {
    long baseSnapshotId = IcebergTableUtil.getSnapshotId(getMixedTable(), true);
    return new UnkeyedTableFileScanHelper(getMixedTable(), baseSnapshotId);
  }

  @Override
  protected UnkeyedTable getMixedTable() {
    return super.getMixedTable().asUnkeyedTable();
  }

  @Override
  protected Map<String, String> buildTaskProperties() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(
        TaskProperties.TASK_EXECUTOR_FACTORY_IMPL,
        MixedIcebergRewriteExecutorFactory.class.getName());
    return properties;
  }
}
