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

import static io.olake.fusion.it.Contract.ProcessStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.olake.fusion.it.Contract.TableProps;
import io.olake.fusion.it.harness.Fusion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot expiry on an OLake-written table, driven the way a user drives it: retention set as
 * table properties, the outcome read from Fusion's API. Each test checks how many snapshots are
 * left, and that at least retain_last are. One table per test, so the tests run in the parallel
 * lane.
 *
 * <p>Retention is in minutes where the product would use days: nothing can be made days old in a
 * test.
 */
@ExtendWith(Report.class)
class SnapshotExpiryIT extends ItBase {

  /** The lowest {@code snapshot.keep.min-count} Fusion accepts. */
  private static final int MIN_COUNT = 10;

  /** Long enough for every snapshot of a setup to fall out of a one-minute retention. */
  private static final Duration RETENTION_PASSES = Duration.ofSeconds(75);

  /**
   * Time between the old and the new snapshots of {@link #retainsSnapshotsNewerThanTheRetention}:
   * more than the up to 3 minutes before a run starts (table refresh, cron minute, expiry tick)
   * plus the minute the retention is rounded to.
   */
  private static final Duration GAP = Duration.ofMinutes(5);

  static final Duration EXPIRY_TIMEOUT = Duration.ofMinutes(5);

  /**
   * retain_last 10, 2 old snapshots older than the retention and 12 newer ones: the 12 newer ones
   * are left.
   */
  @Test
  void retainsSnapshotsNewerThanTheRetention() throws InterruptedException {
    TestTable table = TestTable.create("expiry_split");
    while (snapshots(table).size() < 2) {
      table.load();
    }
    List<Long> old = snapshots(table);
    long oldEnd = old.get(old.size() - 1);
    Thread.sleep(GAP.toMillis());
    while (snapshots(table).size() < old.size() + MIN_COUNT + 2) {
      table.load();
    }
    int newer = snapshots(table).size() - old.size();
    // A cut-off 30 s after the old snapshots for a run starting now, still before the new ones
    // for a run starting 3 minutes later.
    long retentionMinutes = Math.max(1, (System.currentTimeMillis() - oldEnd - 30_000) / 60_000);

    assertSnapshotsLeft(table, retentionMinutes + "min", MIN_COUNT, newer);
  }

  /**
   * retain_last 10, every one of 12 snapshots older than the retention: only the newest 10 are
   * left.
   */
  @Test
  void keepsOnlyTheNewestWhenAllAreOlder() throws InterruptedException {
    TestTable table = tableWithOldSnapshots("expiry_all_old", MIN_COUNT + 2);
    assertSnapshotsLeft(table, "1min", MIN_COUNT, MIN_COUNT);
  }

  /** retain_last 10, none of 11 snapshots older than the retention: every snapshot is left. */
  @Test
  void expiresNothingWhenNoneIsOlder() {
    TestTable table = TestTable.create("expiry_none_old");
    while (snapshots(table).size() < MIN_COUNT + 1) {
      table.load();
    }
    assertSnapshotsLeft(table, "3d", MIN_COUNT, snapshots(table).size());
  }

  /** One expiry run with the given retention succeeds and leaves {@code expected} snapshots. */
  private static void assertSnapshotsLeft(
      TestTable table, String duration, int retainLast, int expected) {
    Fusion.ExpiryRun run = expireOnce(table, duration, String.valueOf(retainLast));
    assertEquals(SUCCESS, run.status(), "status of " + run);
    int left = snapshots(table).size();
    assertEquals(expected, left, "snapshots left after " + run);
    assertTrue(left >= retainLast, "at least retain_last snapshots left after " + run);
  }

  private static List<Long> snapshots(TestTable table) {
    return fusion.snapshotCommitTimes(table.namespace(), table.name());
  }

  /**
   * A table written by OLake syncs until it has at least {@code count} snapshots, which are then
   * older than one minute.
   */
  static TestTable tableWithOldSnapshots(String name, int count) throws InterruptedException {
    TestTable table = TestTable.create(name);
    while (snapshots(table).size() < count) {
      table.load();
    }
    Thread.sleep(RETENTION_PASSES.toMillis());
    return table;
  }

  /**
   * Enables expiry every minute with the given retention, waits for the first run to finish, and
   * sets the expiry cron back to Never.
   */
  static Fusion.ExpiryRun expireOnce(TestTable table, String duration, String minCount) {
    Set<String> known = fusion.expiryRunIds(table.namespace(), table.name());
    setExpiry(table, EVERY_MINUTE, duration, minCount);
    try {
      return fusion.awaitFinishedExpiryRun(table.namespace(), table.name(), known, EXPIRY_TIMEOUT);
    } finally {
      setExpiry(table, TableProps.NEVER, duration, minCount);
    }
  }

  static void setExpiry(TestTable table, String cron, String duration, String minCount) {
    fusion.setTableProperties(
        table.namespace(),
        table.name(),
        Map.of(
            TableProps.SNAPSHOT_EXPIRE_ENABLED,
            "true",
            TableProps.SNAPSHOT_EXPIRE_CRON,
            cron,
            TableProps.SNAPSHOT_KEEP_DURATION,
            duration,
            TableProps.SNAPSHOT_MIN_COUNT,
            minCount));
  }
}
