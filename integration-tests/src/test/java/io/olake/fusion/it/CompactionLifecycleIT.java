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

package io.olake.fusion.it;

import static io.olake.fusion.it.Contract.OptimizingType.FULL;
import static io.olake.fusion.it.Contract.OptimizingType.MAJOR;
import static io.olake.fusion.it.Contract.OptimizingType.MINOR;
import static io.olake.fusion.it.Contract.ProcessStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * One OLake-written table through the three compaction types, in the order a user's crons run them:
 * MINOR turns the equality deletes OLake wrote into position deletes, MAJOR merges those position
 * deletes into the data files, and a FULL on the ingested snapshot (reached again by rolling the
 * table back) clears the equality deletes in one step.
 *
 * <p>The steps share one table on purpose: MAJOR is only given position deletes to merge by the
 * MINOR before it, and the FULL needs the equality deletes that MINOR already converted, which is
 * why it time travels back to the snapshot OLake left. The steps therefore run in order, one at a
 * time: the class is tagged {@code serial} so it runs in the suite's serial lane.
 */
@ExtendWith(Report.class)
@Tag("serial")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompactionLifecycleIT extends ItBase {
  /** The table every step works on, created by the first one. */
  private static TestTable table;

  /** The snapshot OLake ingested: the last one before any compaction, equality deletes and all. */
  private static String ingested;

  /** The run's report keys its evidence by test, and all three steps drive the same table. */
  @BeforeEach
  void reportTable() {
    if (table != null) {
      table.report();
    }
  }

  /**
   * OLake writes an equality delete for a CDC round. A MINOR converts it to position deletes: no
   * equality delete is left, position deletes are, and the data files are untouched, because Fusion
   * converts the deletes of a segment file instead of rewriting the file.
   */
  @Test
  @Order(1)
  void minorConvertsEqualityDeletesToPositionDeletes() {
    table = TestTable.create("lifecycle");
    table.cdc();
    // Every data file must be a segment file for the conversion to be the planned work.
    useSegmentSizedFiles(table);
    requireEqualityDeletes(table, 1);
    ingested = table.currentSnapshotId();
    long dataFiles = dataFiles(table);

    assertProcess(optimize(table, MINOR), MINOR, SUCCESS);

    assertNoEqualityDeletes(table);
    assertTrue(
        positionDeletes(table) > 0,
        "the equality deletes became position deletes: " + table.files());
    assertEquals(
        dataFiles,
        dataFiles(table),
        "MINOR converts the deletes, it does not rewrite the data files");
    assertRows(table);
  }

  /** A MAJOR merges the position deletes the MINOR left into the data files: no delete is left. */
  @Test
  @Order(2)
  void majorMergesPositionDeletesIntoTheDataFiles() {
    assertNotNull(table, "the MINOR step must run first");
    assertTrue(
        positionDeletes(table) > 0,
        "setup error: the MINOR step must leave position deletes to merge");

    assertProcess(optimize(table, MAJOR), MAJOR, SUCCESS);

    assertNoDeleteFiles(table);
    assertRows(table);
  }

  /**
   * A FULL clears the equality deletes on its own, without a MINOR and a MAJOR before it: the table
   * is rolled back to the snapshot OLake ingested, where they are still there, and the FULL leaves
   * no delete file at all.
   */
  @Test
  @Order(3)
  void fullClearsTheEqualityDeletesOfTheIngestedSnapshot() {
    assertNotNull(ingested, "the MINOR step must run first");
    table.rollbackTo(ingested);
    requireEqualityDeletes(table, 1);

    assertProcess(optimize(table, FULL), FULL, SUCCESS);

    assertNoDeleteFiles(table);
    assertRows(table);
  }
}
