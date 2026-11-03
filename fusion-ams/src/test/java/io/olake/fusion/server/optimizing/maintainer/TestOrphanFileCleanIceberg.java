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

package io.olake.fusion.server.optimizing.maintainer;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.formats.iceberg.maintainer.IcebergTableMaintainer;
import io.olake.fusion.io.DataTestHelpers;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.MetadataTableUtils;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(Parameterized.class)
public class TestOrphanFileCleanIceberg extends TestOrphanFileClean {

  private static final Logger LOG = LoggerFactory.getLogger(TestOrphanFileCleanIceberg.class);

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)},
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, false)}
    };
  }

  public TestOrphanFileCleanIceberg(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  @Test
  public void cleanDanglingDeleteFiles() throws IOException {
    List<Record> records = Lists.newArrayListWithCapacity(3);
    records.add(tableTestHelper().generateTestRecord(1, "test1", 0, "2022-01-01T00:00:00"));
    List<DataFile> dataFiles1 = tableTestHelper().writeDataFiles(getTable(), records);
    Table testTable = getTable();
    AppendFiles appendFiles = testTable.newAppend();
    dataFiles1.forEach(appendFiles::appendFile);
    appendFiles.commit();
    assertDanglingDeleteFiles(testTable, 0);
    List<DeleteFile> posDelete =
        DataTestHelpers.writePosDeletes(
            testTable, dataFiles1.get(0), Collections.singletonList(0L));
    testTable.newRowDelta().addDeletes(posDelete.get(0)).commit();
    records.clear();
    records.add(tableTestHelper().generateTestRecord(3, "test3", 0, "2022-01-02T00:00:00"));
    List<DataFile> dataFiles2 = tableTestHelper().writeDataFiles(getTable(), records);
    testTable
        .newRewrite()
        .rewriteFiles(
            Collections.singleton(dataFiles1.get(0)), Collections.singleton(dataFiles2.get(0)))
        .validateFromSnapshot(testTable.currentSnapshot().snapshotId())
        .commit();
    assertDanglingDeleteFiles(testTable, 1);

    IcebergTableMaintainer tableMaintainer =
        new IcebergTableMaintainer(
            testTable,
            getFusionTable().io(),
            getFusionTable().id(),
            TestTableMaintainerContext.of(testTable));
    tableMaintainer.doCleanDanglingDeleteFiles();

    assertDanglingDeleteFiles(testTable, 0);
  }

  private void assertDanglingDeleteFiles(Table unkeyedTable, int count) {
    TableScan tableScan = unkeyedTable.newScan();
    Set<String> files = new HashSet<>();
    for (FileScanTask task : tableScan.planFiles()) {
      files.add(task.file().path().toString());
      for (DeleteFile delete : task.deletes()) {
        files.add(delete.path().toString());
      }
    }
    Set<String> danglingDeleteFiles = new HashSet<>();
    Table manifestTable =
        MetadataTableUtils.createMetadataTableInstance(
            ((HasTableOperations) unkeyedTable).operations(),
            unkeyedTable.name(),
            metadataTableName(unkeyedTable.name(), MetadataTableType.ENTRIES),
            MetadataTableType.ENTRIES);
    try (CloseableIterable<Record> entries = IcebergGenerics.read(manifestTable).build()) {
      for (Record entry : entries) {
        GenericRecord dataFile = (GenericRecord) entry.get(4);
        int status = (int) entry.getField("status");
        String filePath = (String) dataFile.getField(DataFile.FILE_PATH.name());
        if (status != 2 && !files.contains(filePath)) {
          danglingDeleteFiles.add(filePath);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    for (String danglingDeleteFile : danglingDeleteFiles) {
      LOG.info("find dangling delete files {}", danglingDeleteFile);
    }
    Assert.assertEquals(count, danglingDeleteFiles.size());
  }

  private static String metadataTableName(String tableName, MetadataTableType type) {
    return tableName + (tableName.contains("/") ? "#" : ".") + type;
  }
}
