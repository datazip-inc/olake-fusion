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

package io.olake.fusion.server.optimizing.scan;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.io.DataTestHelpers;
import io.olake.fusion.optimizing.scan.IcebergTableFileScanHelper;
import io.olake.fusion.optimizing.scan.TableFileScanHelper;
import io.olake.fusion.server.optimizing.OptimizingTestHelpers;
import io.olake.fusion.server.utils.IcebergTableUtil;
import io.olake.fusion.utils.ExpressionUtil;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(Parameterized.class)
public class TestUnkeyedTableFileScanHelper extends TableFileScanHelperTestBase {
  public TestUnkeyedTableFileScanHelper(
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
  public void testScanEmpty() {
    List<TableFileScanHelper.FileScanResult> scan = scanFiles();
    assertScanResult(scan, 0);
  }

  @Test
  public void testScanEmptySnapshot() {
    OptimizingTestHelpers.appendBase(
        getTable(), tableTestHelper().writeDataFiles(getTable(), Collections.emptyList()));

    List<TableFileScanHelper.FileScanResult> scan = scanFiles();
    assertScanResult(scan, 0);
  }

  @Test
  public void testScan() {
    ArrayList<Record> newRecords =
        Lists.newArrayList(
            tableTestHelper().generateTestRecord(1, "111", 0, "2022-01-01T12:00:00"),
            tableTestHelper().generateTestRecord(2, "222", 0, "2022-01-01T12:00:00"),
            tableTestHelper().generateTestRecord(3, "333", 0, "2022-01-02T12:00:00"),
            tableTestHelper().generateTestRecord(4, "444", 0, "2022-01-02T12:00:00"));
    List<DataFile> dataFiles =
        OptimizingTestHelpers.appendBase(
            getTable(), tableTestHelper().writeDataFiles(getTable(), newRecords));
    // partition field = "2022-01-01T12:00:00"
    DataFile sampleFile = dataFiles.get(0);

    OptimizingTestHelpers.appendBase(
        getTable(), tableTestHelper().writeDataFiles(getTable(), newRecords));

    List<TableFileScanHelper.FileScanResult> scan = scanFiles();

    if (isPartitionedTable()) {
      assertScanResult(scan, 4, null, 0);
    } else {
      assertScanResult(scan, 2, null, 0);
    }

    // test partition filter
    scan =
        scanFiles(
            buildFileScanHelper()
                .withPartitionFilter(
                    ExpressionUtil.convertPartitionDataToDataFilter(
                        getTable(), sampleFile.specId(), sampleFile.partition())));
    assertScanResult(scan, 2, null, 0);
  }

  @Test
  public void testScanWithPosDelete() {
    ArrayList<Record> newRecords =
        Lists.newArrayList(
            tableTestHelper().generateTestRecord(1, "111", 0, "2022-01-01T12:00:00"),
            tableTestHelper().generateTestRecord(2, "222", 0, "2022-01-01T12:00:00"),
            tableTestHelper().generateTestRecord(3, "333", 0, "2022-01-01T12:00:00"),
            tableTestHelper().generateTestRecord(4, "444", 0, "2022-01-01T12:00:00"));
    List<DataFile> dataFiles =
        OptimizingTestHelpers.appendBase(
            getTable(), tableTestHelper().writeDataFiles(getTable(), newRecords));
    List<DeleteFile> posDeleteFiles = Lists.newArrayList();
    for (DataFile dataFile : dataFiles) {
      posDeleteFiles.addAll(
          DataTestHelpers.writePosDeletes(getTable(), dataFile, Collections.singletonList(0L)));
    }
    OptimizingTestHelpers.appendBasePosDelete(getTable(), posDeleteFiles);

    List<TableFileScanHelper.FileScanResult> scan = scanFiles();

    assertScanResult(scan, 1, null, 1);
  }

  @Override
  protected Table getTable() {
    return super.getTable();
  }

  @Override
  protected TableFileScanHelper buildFileScanHelper() {
    long baseSnapshotId = IcebergTableUtil.getSnapshotId(getTable(), true);
    return new IcebergTableFileScanHelper(getTable(), baseSnapshotId);
  }
}
