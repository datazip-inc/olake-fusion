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

package org.apache.amoro.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.IcebergFixture.WrittenDataFile;
import org.apache.amoro.it.support.TableLayout;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full optimization: every partition is rewritten down to insert files only, with no delete files
 * left, the row set is unchanged, and the process is recorded as FULL.
 *
 * <p>Layout per partition: 2 undersized segment files, 3 fragment files, an equality delete and a
 * position delete.
 */
class FullOptimizationIT extends OptimizationTestBase {

  @ParameterizedTest
  @EnumSource(Layout.class)
  void fullRewritesPartitionsToInsertFilesOnly(Layout layout) {
    String name = IcebergFixture.uniqueName("it_full_" + layout.name().toLowerCase());
    Table table = iceberg.createTable(name, layout, optimizationProperties());

    List<WrittenDataFile> segment1 = appendFiles(table, layout, UNDERSIZED_SEGMENT_ROWS);
    appendFiles(table, layout, UNDERSIZED_SEGMENT_ROWS);
    List<WrittenDataFile> fragments = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      fragments.addAll(appendFiles(table, layout, FRAGMENT_ROWS));
    }

    List<Record> deleted = new ArrayList<>();
    List<DeleteFile> deletes = new ArrayList<>();
    for (WrittenDataFile file : segment1) {
      List<Long> positions = IcebergFixture.positions(0, 5);
      deletes.add(iceberg.writePositionDeletes(table, file.file(), positions));
      deleted.addAll(IcebergFixture.rowsAt(file, positions));
    }
    List<Record> eqDeleted = new ArrayList<>();
    for (WrittenDataFile file : fragments.subList(0, partitions(layout))) {
      eqDeleted.addAll(file.rows().subList(0, 10));
    }
    deletes.addAll(iceberg.writeEqualityDeletes(table, eqDeleted));
    deleted.addAll(eqDeleted);
    IcebergFixture.commitDeletes(table, deletes);

    List<String> expected = IcebergFixture.expectedRows(written, deleted);
    assertEquals(expected, IcebergFixture.readRows(table), "row set before optimization");
    TableLayout before = IcebergFixture.layout(table);

    JsonNode process = runOptimization(table, name, "FULL");
    assertSucceededAs(process, "FULL");

    TableLayout after = IcebergFixture.layout(table);
    assertTrue(after.deleteFiles().isEmpty(), "no delete files left: " + after);
    for (Map.Entry<String, int[]> partition : after.filesPerPartition().entrySet()) {
      assertTrue(partition.getValue()[0] > 0, "partition " + partition.getKey() + " has data");
      assertEquals(0, partition.getValue()[1], "delete files in " + partition.getKey());
    }
    assertSamePartitions(before, after);
    assertEquals(expected, IcebergFixture.readRows(table), "row set after optimization");
  }
}
