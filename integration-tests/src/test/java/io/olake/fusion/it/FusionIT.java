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
import static io.olake.fusion.it.Contract.ProcessStatus.SKIPPED;
import static io.olake.fusion.it.Contract.ProcessStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.olake.fusion.it.Contract.SkipReason;
import io.olake.fusion.it.harness.Await;
import io.olake.fusion.it.harness.Fusion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

/**
 * What a Fusion user does, one test per behavior, each on its own unpartitioned table. Every table
 * is created and written by OLake: the test changes the source database and syncs, which is the
 * only way Fusion's tables come into being in the product. Fusion is configured and triggered
 * through the table settings OLake-UI writes; results are read back through Spark and Fusion's REST
 * API.
 *
 * <p>What each compaction type does to the files of a table is in {@link CompactionLifecycleIT},
 * which drives one table through MINOR, MAJOR and FULL in that order.
 */
@ExtendWith(Report.class)
class FusionIT extends ItBase {
  /**
   * Rows per sync in the health-score test. Small enough that every file stays below the minimum
   * target size (so the score counts it), many enough that the table as a whole reaches it.
   */
  private static final int HEALTH_ROWS = 600;

  private static final int HEALTH_SYNCS = 6;

  // Priority: when several crons fire in the same minute, only the highest type runs.

  @Test
  void fullBeatsMajorAndMinor() {
    TestTable t = eligibleForAllTypes("prio_full");
    assertProcess(optimizeOnly(t, FULL, FULL, MAJOR, MINOR), FULL, SUCCESS);
    assertRows(t);
  }

  @Test
  void majorBeatsMinor() {
    TestTable t = eligibleForAllTypes("prio_major");
    assertProcess(optimizeOnly(t, MAJOR, MAJOR, MINOR), MAJOR, SUCCESS);
    assertRows(t);
  }

  // Nothing to do

  /**
   * After a FULL has left no deletes, the next FULL cron finds nothing to do: the process is
   * SKIPPED, no snapshot is committed, and the run history says why it was skipped.
   */
  @Test
  void nothingToDoIsSkippedWithAReason() {
    TestTable t = TestTable.create("nothing_to_do");
    t.cdc();
    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    String snapshot = t.currentSnapshotId();

    Fusion.Process skipped = cronFiresOnce(t, FULL);
    assertProcess(skipped, FULL, SKIPPED);
    assertEquals(snapshot, t.currentSnapshotId(), "no new snapshot");
    assertRows(t);

    String reason = skipped.skipReason();
    assertNotNull(reason, "skip reason in the process metrics of " + skipped);
    assertTrue(reason.contains(SkipReason.CRON_FIRED), "skip reason names the cron: " + reason);
    assertTrue(reason.contains(FULL), "skip reason names the optimizing type: " + reason);
    assertTrue(reason.contains(SkipReason.NO_DATA), "skip reason says why: " + reason);
    assertEquals(reason, skipped.failReason(), "the process carries the same reason");
  }

  // Health score

  /**
   * The score scales its penalties by the table's file count and size, so a handful of tiny OLake
   * files keeps a table at 100. {@link #HEALTH_SYNCS} syncs of {@link #HEALTH_ROWS} rows each give
   * it enough files and bytes to be penalized; a snapshot scored mid-setup can still be at 100, so
   * the first wait is for a score below 100.
   */
  @Test
  void healthScoreImprovesAfterFull() {
    TestTable t = TestTable.create("health");
    t.cdc();
    for (int i = 0; i < HEALTH_SYNCS; i++) {
      t.load(HEALTH_ROWS);
    }
    long before =
        Await.until(
            "a health score below 100 for " + t.ident(),
            Duration.ofMinutes(3),
            t::healthScore,
            score -> score >= 0 && score < 100);

    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    Await.until(
        "health score above " + before,
        Duration.ofMinutes(3),
        t::healthScore,
        score -> score > before);
  }

  // Helpers

  /** Several data files and deletes, so only priority decides the type. */
  private static TestTable eligibleForAllTypes(String name) {
    TestTable t = TestTable.create(name);
    t.cdc();
    t.load();
    requireDeleteFiles(t);
    return t;
  }
}
