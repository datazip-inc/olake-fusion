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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.IcebergFixture.WrittenDataFile;
import org.apache.amoro.it.support.TableLayout;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Minor optimization: many small files become fewer, larger files, equality deletes are rewritten
 * to position deletes, the row set is unchanged and the process reaches SUCCESS.
 *
 * <p>Layout per partition: one target-size-reached file, 8 fragment files, and one equality delete
 * file that hits rows in both. Minor merges the fragments (applying the deletes) and converts the
 * equality deletes on the large file to position deletes.
 */
class MinorOptimizationIT extends OptimizationTestBase {

  private static final int FRAGMENTS = 8;

  @ParameterizedTest
  @EnumSource(Layout.class)
  void minorMergesSmallFilesAndConvertsEqualityDeletes(Layout layout) {
    String name = IcebergFixture.uniqueName("it_minor_" + layout.name().toLowerCase());
    Table table = iceberg.createTable(name, layout, optimizationProperties());

    List<WrittenDataFile> large = appendFiles(table, layout, TARGET_REACHED_ROWS);
    assertSizeBetween(large, IcebergFixture.MIN_TARGET_SIZE, Long.MAX_VALUE);
    List<WrittenDataFile> fragments = new ArrayList<>();
    for (int i = 0; i < FRAGMENTS; i++) {
      fragments.addAll(appendFiles(table, layout, FRAGMENT_ROWS));
    }
    assertSizeBetween(fragments, 0, IcebergFixture.FRAGMENT_MAX_SIZE);

    List<Record> deleted = new ArrayList<>();
    for (WrittenDataFile file : large) {
      deleted.addAll(file.rows().subList(0, 20));
    }
    for (WrittenDataFile file : fragments.subList(0, partitions(layout))) {
      deleted.addAll(file.rows().subList(0, 10));
    }
    IcebergFixture.commitDeletes(table, iceberg.writeEqualityDeletes(table, deleted));

    List<String> expected = IcebergFixture.expectedRows(written, deleted);
    assertEquals(expected, IcebergFixture.readRows(table), "row set before optimization");
    TableLayout before = IcebergFixture.layout(table);
    assertFalse(before.equalityDeletes().isEmpty(), "setup wrote equality deletes: " + before);

    JsonNode process = runOptimization(table, name, "MINOR");
    assertSucceededAs(process, "MINOR");

    TableLayout after = IcebergFixture.layout(table);
    assertTrue(
        after.dataFiles().size() < before.dataFiles().size(),
        "fewer data files; before=" + before + " after=" + after);
    assertTrue(after.equalityDeletes().isEmpty(), "no equality deletes left: " + after);
    assertSamePartitions(before, after);
    assertEquals(expected, IcebergFixture.readRows(table), "row set after optimization");
  }
}
