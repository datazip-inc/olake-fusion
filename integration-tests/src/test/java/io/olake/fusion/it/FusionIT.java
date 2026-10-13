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
import io.olake.fusion.it.harness.SizeClass;
import io.olake.fusion.it.harness.SparkTable;
import io.olake.fusion.it.harness.SparkTable.FileInfo;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

/**
 * What a Fusion user does, one test per behavior, each on its own table. Data comes in the way a
 * user writes it (OLake CDC, Spark SQL); Fusion is triggered through table crons; results are read
 * back through Spark and Fusion's REST API.
 *
 * <p>Compaction runs for a partitioned and an unpartitioned table; everything else runs on a
 * partitioned table.
 */
class FusionIT extends ItBase {
  private static final List<String> REGIONS = List.of("ap", "eu", "us");

  // Compaction: both layouts

  @ParameterizedTest(name = "partitioned={0}")
  @ValueSource(booleans = {true, false})
  void minorCompaction(boolean partitioned) {
    TestTable t = TestTable.olake("minor_" + partitioned, partitioned);
    t.cdc();
    requireDeletes(t, true, partitions(t));
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, MINOR), MINOR, SUCCESS);
    assertRows(t);
    assertFiles(t, MINOR, before, partitions(t));
    assertFusionAgrees(t);
  }

  @ParameterizedTest(name = "partitioned={0}")
  @ValueSource(booleans = {true, false})
  void majorCompaction(boolean partitioned) {
    TestTable t = TestTable.olake("major_" + partitioned, partitioned);
    t.cdc();
    regions(t).forEach(r -> t.append(r, SizeClass.UNDERSIZED_SEGMENT, 2));
    requireDeletes(t, true, partitions(t));
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, MAJOR), MAJOR, SUCCESS);
    assertRows(t);
    assertFiles(t, MAJOR, before, partitions(t));
    assertFusionAgrees(t);
  }

  @ParameterizedTest(name = "partitioned={0}")
  @ValueSource(booleans = {true, false})
  void fullCompaction(boolean partitioned) {
    TestTable t = TestTable.olake("full_" + partitioned, partitioned);
    t.cdc();
    requireDeletes(t, true, partitions(t));
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    assertRows(t);
    assertFiles(t, FULL, before, partitions(t));
    assertFusionAgrees(t);
  }

  /** No deletes: MAJOR is decided by the "more than one data file" branch. */
  @ParameterizedTest(name = "partitioned={0}")
  @ValueSource(booleans = {true, false})
  void majorWithoutDeletes(boolean partitioned) {
    TestTable t = TestTable.spark("major_nodel_" + partitioned, partitioned);
    regions(t).forEach(r -> t.append(r, SizeClass.UNDERSIZED_SEGMENT, 2));
    requireDeletes(t, false, partitions(t));
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, MAJOR), MAJOR, SUCCESS);
    assertRows(t);
    assertFiles(t, MAJOR, before, partitions(t));
    assertFusionAgrees(t);
  }

  /** No deletes: FULL is decided by the "two or more fragments" branch. */
  @ParameterizedTest(name = "partitioned={0}")
  @ValueSource(booleans = {true, false})
  void fullFragmentsOnly(boolean partitioned) {
    TestTable t = TestTable.spark("full_frag_" + partitioned, partitioned);
    regions(t).forEach(r -> t.append(r, SizeClass.FRAGMENT, 3));
    requireDeletes(t, false, partitions(t));
    List<FileInfo> before = t.table.files();

    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    assertRows(t);
    assertFiles(t, FULL, before, partitions(t));
    assertFusionAgrees(t);
  }

  // Priority: partitioned

  @Test
  void fullBeatsMajorAndMinor() {
    TestTable t = eligibleForAllTypes("prio_full");
    assertProcess(optimize(t, FULL, MAJOR, MINOR), FULL, SUCCESS);
    assertRows(t);
    assertFusionAgrees(t);
  }

  @Test
  void majorBeatsMinor() {
    TestTable t = eligibleForAllTypes("prio_major");
    assertProcess(optimize(t, MAJOR, MINOR), MAJOR, SUCCESS);
    assertRows(t);
    assertFusionAgrees(t);
  }

  // Nothing to do: partitioned

  /**
   * After a FULL has left one data file and no deletes per partition, the next FULL cron finds
   * nothing to do: the process is SKIPPED and no snapshot is committed.
   */
  @Test
  void nothingToDoIsSkipped() {
    TestTable t = TestTable.spark("nothing_to_do", true);
    REGIONS.forEach(
        r -> {
          t.append(r, SizeClass.FRAGMENT, 3);
          t.deleteRows(r);
        });
    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    String snapshot = t.table.currentSnapshotId();

    assertProcess(cronFiresOnce(t, FULL), FULL, SKIPPED);
    assertEquals(snapshot, t.table.currentSnapshotId(), "no new snapshot");
    assertRows(t);
  }

  // Cron: partitioned, alone (timing)

  @Test
  @Tag("serial")
  void cronFiresWithinAMinute() {
    TestTable t = TestTable.spark("cron", true);
    regions(t)
        .forEach(
            r -> {
              t.append(r, SizeClass.FRAGMENT, 3);
              t.deleteRows(r);
            });
    long enabledAt = System.currentTimeMillis();

    Fusion.Process run = optimize(t, MINOR);
    assertTrue(
        run.startTime() - enabledAt <= Duration.ofSeconds(60 + 90).toMillis(),
        "started within a minute plus 90 s of setting the cron: " + run);
    assertProcess(run, MINOR, SUCCESS);
  }

  // Health score: partitioned

  @Test
  @Disabled("health score is not implemented yet; Fusion serves -1")
  void healthScoreImprovesAfterFull() {
    TestTable t = TestTable.spark("health", true);
    regions(t)
        .forEach(
            r -> {
              t.append(r, SizeClass.FRAGMENT, 3);
              t.deleteRows(r);
            });
    long before =
        Await.until(
            "a health score for " + t.table.ident(),
            Duration.ofMinutes(3),
            () -> fusion.healthScore(t.table.namespace(), t.table.name()),
            score -> score >= 0);

    assertProcess(optimize(t, FULL), FULL, SUCCESS);
    Await.until(
        "health score above " + before,
        Duration.ofMinutes(3),
        () -> fusion.healthScore(t.table.namespace(), t.table.name()),
        score -> score > before);
  }

  // Plan failure: partitioned

  /**
   * One data manifest is moved out of place in the bucket (the agreed exception to the black-box
   * rule): planning reads manifests, so the MAJOR must fail before creating any task and commit
   * nothing. With the manifest back, the next MAJOR succeeds.
   */
  @Test
  void planFailureOnMissingManifest() {
    TestTable t = TestTable.spark("planfail", true);
    t.append("eu", SizeClass.UNDERSIZED_SEGMENT, 4);
    t.deleteRows("eu");
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

  /** Every partition: 2 undersized segments, 3 fragments and deletes, so only priority decides. */
  private static TestTable eligibleForAllTypes(String name) {
    TestTable t = TestTable.spark(name, true);
    for (String region : REGIONS) {
      t.append(region, SizeClass.UNDERSIZED_SEGMENT, 2);
      t.append(region, SizeClass.FRAGMENT, 3);
      t.deleteRows(region);
    }
    requireDeletes(t, true, partitions(t));
    return t;
  }

  private static List<String> regions(TestTable t) {
    return t.table.partitioned() ? REGIONS : List.of("eu");
  }

  private static String[] partitions(TestTable t) {
    return t.table.partitioned()
        ? REGIONS.stream().map(t::partition).toArray(String[]::new)
        : new String[] {""};
  }
}
