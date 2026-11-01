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

package io.olake.fusion.server.scheduler.inline;

import io.olake.fusion.TableTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.catalog.TableTestBase;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableList;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ReachableFileUtil;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.junit.Assert;

import java.time.LocalDateTime;
import java.util.List;

public class ExecutorTestBase extends TableTestBase {

  public ExecutorTestBase(CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  public List<Record> createRecords(int start, int length) {
    ImmutableList.Builder<Record> builder = ImmutableList.builder();
    for (int i = start; i < start + length; i++) {
      builder.add(
          tableTestHelper()
              .generateTestRecord(
                  i, "name" + i, 0L, LocalDateTime.of(2022, 1, i % 2 + 1, 12, 0, 0).toString()));
    }
    return builder.build();
  }

  public List<DataFile> writeAndCommitBaseStore(Table table) {
    Table baseTable = table;
    // write 4 file,100 records to 2 partitions(2022-1-1\2022-1-2)
    List<DataFile> dataFiles = tableTestHelper().writeDataFiles(baseTable, createRecords(1, 100));
    AppendFiles appendFiles = baseTable.newAppend();
    dataFiles.forEach(appendFiles::appendFile);
    appendFiles.commit();
    return dataFiles;
  }

  public static void assertMetadataExists(Table table) {
    checkMetadataExistence(table);
  }

  public static void checkMetadataExistence(Table table) {
    for (Snapshot snapshot : table.snapshots()) {
      Assert.assertTrue(
          table.io().newInputFile(String.valueOf(snapshot.manifestListLocation())).exists());
      for (ManifestFile allManifest : snapshot.allManifests(table.io())) {
        Assert.assertTrue(table.io().newInputFile(String.valueOf(allManifest.path())).exists());
      }
    }
    for (String metadataFile : ReachableFileUtil.metadataFileLocations(table, false)) {
      Assert.assertTrue(table.io().newInputFile(String.valueOf(metadataFile)).exists());
    }
    Assert.assertTrue(
        table
            .io()
            .newInputFile(String.valueOf(ReachableFileUtil.versionHintLocation(table)))
            .exists());
  }
}
