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

import static io.olake.fusion.it.contract.OptimizingType.FULL;
import static io.olake.fusion.it.contract.OptimizingType.MAJOR;
import static io.olake.fusion.it.contract.OptimizingType.MINOR;
import static io.olake.fusion.it.contract.ProcessStatus.FAILED;
import static io.olake.fusion.it.contract.ProcessStatus.SKIPPED;
import static io.olake.fusion.it.contract.ProcessStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.olake.fusion.it.harness.Await;
import io.olake.fusion.it.harness.Bucket;
import io.olake.fusion.it.harness.Fusion;
import io.olake.fusion.it.harness.SparkTable;
import io.olake.fusion.it.harness.SparkTable.FileInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

/**
 * What a Fusion user does, one test per behavior, each on its own unpartitioned table. Every table
 * is created and written by OLake: the test changes the source database and syncs, which is the
 * only way Fusion's tables come into being in the product. Fusion is configured and triggered
 * through the table settings OLake-UI writes; results are read back through Spark and Fusion's REST
 * API.
 */
class FusionIT extends ItBase {
  /**
   * Rows per sync in the health-score test. Small enough that every file stays below the minimum
   * target size (so the score counts it), many enough that the table as a whole reaches it.
   */
  private static final int HEALTH_ROWS = 600;

  private static final int HEALTH_SYNCS = 6;

  // Compaction

  @Test
  void minorCompaction() {
    TestTable t = TestTable.create("minor");
    t.cdc();
    requireDeletes(t, true);
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, MINOR), MINOR, SUCCESS);
    assertRows(t);
    assertFiles(t, MINOR, before);
  }

  @Test
  void majorCompaction() {
    TestTable t = TestTable.create("major");
    t.cdc();
    t.load();
    requireDeletes(t, true);
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, MAJOR), MAJOR, SUCCESS);
    assertRows(t);
    assertFiles(t, MAJOR, before);
  }

  @Test
  void fullCompaction() {
    TestTable t = TestTable.create("full");
    t.cdc();
    requireDeletes(t, true);
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    assertRows(t);
    assertFiles(t, FULL, before);
  }

  /** No deletes: MAJOR is decided by the "more than one data file" branch. */
  @Test
  void majorWithoutDeletes() {
    TestTable t = insertsOnly("major_nodel");
    requireDeletes(t, false);
    requireDataFiles(t, 2);
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, MAJOR), MAJOR, SUCCESS);
    assertRows(t);
    assertFiles(t, MAJOR, before);
  }

  /** No deletes: FULL is decided by the "two or more fragments" branch. */
  @Test
  void fullFragmentsOnly() {
    TestTable t = insertsOnly("full_frag");
    requireDeletes(t, false);
    requireDataFiles(t, 2);
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    assertRows(t);
    assertFiles(t, FULL, before);
  }

  // Priority

  @Test
  void fullBeatsMajorAndMinor() {
    TestTable t = eligibleForAllTypes("prio_full");
    assertProcess(optimize(t, FULL, MAJOR, MINOR), FULL, SUCCESS);
    assertRows(t);
  }

  @Test
  void majorBeatsMinor() {
    TestTable t = eligibleForAllTypes("prio_major");
    assertProcess(optimize(t, MAJOR, MINOR), MAJOR, SUCCESS);
    assertRows(t);
  }

  // Nothing to do

  /**
   * After a FULL has left one data file and no deletes, the next FULL cron finds nothing to do: the
   * process is SKIPPED and no snapshot is committed.
   */
  @Test
  void nothingToDoIsSkipped() {
    TestTable t = TestTable.create("nothing_to_do");
    t.cdc();
    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    String snapshot = t.table.currentSnapshotId();

    assertProcess(cronFiresOnce(t, FULL), FULL, SKIPPED);
    assertEquals(snapshot, t.table.currentSnapshotId(), "no new snapshot");
    assertRows(t);
  }

  // Cron: alone (timing)

  @Test
  @Tag("serial")
  void cronFiresWithinAMinute() {
    TestTable t = TestTable.create("cron");
    t.cdc();
    long enabledAt = System.currentTimeMillis();

    Fusion.Process run = optimize(t, MINOR);
    assertTrue(
        run.startTime() - enabledAt <= Duration.ofSeconds(60 + 90).toMillis(),
        "started within a minute plus 90 s of setting the cron: " + run);
    assertProcess(run, MINOR, SUCCESS);
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
            "a health score below 100 for " + t.table.ident(),
            Duration.ofMinutes(3),
            () -> fusion.healthScore(t.table.namespace(), t.table.name()),
            score -> score >= 0 && score < 100);

    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    Await.until(
        "health score above " + before,
        Duration.ofMinutes(3),
        () -> fusion.healthScore(t.table.namespace(), t.table.name()),
        score -> score > before);
  }

  // Plan failure

  /**
   * One data manifest is moved out of place in the bucket (the agreed exception to the black-box
   * rule): planning reads manifests, so the MAJOR must fail before creating any task and commit
   * nothing. With the manifest back, the next MAJOR succeeds.
   */
  @Test
  void planFailureOnMissingManifest() {
    TestTable t = TestTable.create("planfail");
    t.cdc();
    t.load();
    String snapshot = t.table.currentSnapshotId();
    String manifest =
        SparkTable.sql("SELECT path FROM " + t.table.ident() + ".manifests WHERE content = 0")
            .get(0)
            .getString(0);

    Fusion.Process failed;
    Bucket.move(manifest, manifest + ".moved-by-test");
    try {
      failed = optimize(t, MAJOR);
    } finally {
      Bucket.move(manifest + ".moved-by-test", manifest);
    }
    assertProcess(failed, MAJOR, FAILED);
    assertEquals(0, failed.totalTasks(), "failed in planning, before any task: " + failed);
    assertEquals(snapshot, t.table.currentSnapshotId(), "a failed process commits nothing");

    assertProcess(optimize(t, MAJOR), MAJOR, SUCCESS);
    assertRows(t);
  }

  // Helpers

  /** Several data files and deletes, so only priority decides the type. */
  private static TestTable eligibleForAllTypes(String name) {
    TestTable t = TestTable.create(name);
    t.cdc();
    t.load();
    requireDeletes(t, true);
    return t;
  }

  /**
   * A table with several data files and no delete file. OLake writes an equality delete for every
   * source update and delete, and also for the inserts of the first sync after the backfill, so a
   * FULL first clears the table to one data file and no deletes; the plain insert syncs after it
   * add data files only.
   */
  private static TestTable insertsOnly(String name) {
    TestTable t = TestTable.create(name);
    t.load();
    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    t.load();
    return t;
  }
}
