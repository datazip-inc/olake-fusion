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

/**
 * Major optimization: segment files plus equality and position deletes are merged, redundant delete
 * data is gone, the row set is unchanged, and the process is recorded as MAJOR, not MINOR.
 *
 * <p>Layout per partition: 4 undersized segment files (enough content to merge), a position delete
 * removing 25% of the first file (above the 10% duplicate ratio, so that file must be rewritten), a
 * small position delete on the second file, and an equality delete on the third.
 */
class MajorOptimizationIT extends OptimizationTestBase {

  private static final int SEGMENTS = 4;

  @ParameterizedTest
  @EnumSource(Layout.class)
  void majorMergesSegmentsAndDropsRedundantDeletes(Layout layout) {
    String name = IcebergFixture.uniqueName("it_major_" + layout.name().toLowerCase());
    Table table = iceberg.createTable(name, layout, optimizationProperties());

    List<List<WrittenDataFile>> segments = new ArrayList<>();
    for (int i = 0; i < SEGMENTS; i++) {
      List<WrittenDataFile> files = appendFiles(table, layout, UNDERSIZED_SEGMENT_ROWS);
      assertSizeBetween(files, IcebergFixture.FRAGMENT_MAX_SIZE, IcebergFixture.MIN_TARGET_SIZE);
      segments.add(files);
    }

    List<Record> deleted = new ArrayList<>();
    List<DeleteFile> deletes = new ArrayList<>();
    int heavy = UNDERSIZED_SEGMENT_ROWS / 4;
    for (WrittenDataFile file : segments.get(0)) {
      List<Long> positions = IcebergFixture.positions(0, heavy);
      deletes.add(iceberg.writePositionDeletes(table, file.file(), positions));
      deleted.addAll(IcebergFixture.rowsAt(file, positions));
    }
    for (WrittenDataFile file : segments.get(1)) {
      List<Long> positions = IcebergFixture.positions(0, 30);
      deletes.add(iceberg.writePositionDeletes(table, file.file(), positions));
      deleted.addAll(IcebergFixture.rowsAt(file, positions));
    }
    List<Record> eqDeleted = new ArrayList<>();
    for (WrittenDataFile file : segments.get(2)) {
      eqDeleted.addAll(file.rows().subList(0, 20));
    }
    deletes.addAll(iceberg.writeEqualityDeletes(table, eqDeleted));
    deleted.addAll(eqDeleted);
    IcebergFixture.commitDeletes(table, deletes);

    List<String> expected = IcebergFixture.expectedRows(written, deleted);
    assertEquals(expected, IcebergFixture.readRows(table), "row set before optimization");
    TableLayout before = IcebergFixture.layout(table);

    JsonNode process = runOptimization(table, name, "MAJOR");
    assertSucceededAs(process, "MAJOR");

    TableLayout after = IcebergFixture.layout(table);
    assertTrue(after.equalityDeletes().isEmpty(), "no equality deletes left: " + after);
    assertTrue(
        after.deleteRecordCount() < before.deleteRecordCount(),
        "delete records reduced; before=" + before + " after=" + after);
    assertTrue(
        after.dataFiles().size() <= before.dataFiles().size(),
        "segments merged, not split; before=" + before + " after=" + after);
    assertTrue(
        IcebergFixture.danglingDeleteFiles(table).isEmpty(),
        "no delete file left that applies to nothing: " + after);
    assertSamePartitions(before, after);
    assertEquals(expected, IcebergFixture.readRows(table), "row set after optimization");
  }

  /**
   * MAJOR with no delete files, so the major interval check is decided by the dataFileCount > 1
   * branch rather than by anyDeleteExist. Layout per partition: 4 undersized segment files.
   */
  @ParameterizedTest
  @EnumSource(Layout.class)
  void majorMergesSegmentsWithoutDeletes(Layout layout) {
    String name = IcebergFixture.uniqueName("it_major_nodel_" + layout.name().toLowerCase());
    Table table = iceberg.createTable(name, layout, optimizationProperties());

    List<WrittenDataFile> segments = new ArrayList<>();
    for (int i = 0; i < SEGMENTS; i++) {
      segments.addAll(appendFiles(table, layout, UNDERSIZED_SEGMENT_ROWS));
    }
    assertSizeBetween(segments, IcebergFixture.FRAGMENT_MAX_SIZE, IcebergFixture.MIN_TARGET_SIZE);

    List<String> expected = IcebergFixture.render(written);
    assertEquals(expected, IcebergFixture.readRows(table), "row set before optimization");
    TableLayout before = IcebergFixture.layout(table);
    assertTrue(before.deleteFiles().isEmpty(), "setup wrote no delete files: " + before);

    JsonNode process = runOptimization(table, name, "MAJOR");
    assertSucceededAs(process, "MAJOR");

    TableLayout after = IcebergFixture.layout(table);
    assertTrue(after.deleteFiles().isEmpty(), "no delete files: " + after);
    assertTrue(
        after.dataFiles().size() < before.dataFiles().size(),
        "segments merged; before=" + before + " after=" + after);
    assertSamePartitions(before, after);
    assertEquals(expected, IcebergFixture.readRows(table), "row set after optimization");
  }
}
