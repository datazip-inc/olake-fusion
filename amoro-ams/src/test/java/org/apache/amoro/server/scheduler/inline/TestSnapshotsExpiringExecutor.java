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

package org.apache.amoro.server.scheduler.inline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.amoro.AmoroTable;
import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.TableFormat;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.config.TableConfiguration;
import org.apache.amoro.formats.iceberg.maintainer.SnapshotExpirySummary;
import org.apache.amoro.server.persistence.SnapshotExpiryTestBase;
import org.apache.amoro.server.table.DefaultTableRuntime;
import org.apache.amoro.server.table.TableConfigurations;
import org.apache.amoro.server.table.TableService;
import org.apache.amoro.server.utils.Telemetry;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.utils.JacksonUtil;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** {@link SnapshotsExpiringExecutor} on a real Postgres: one {@code execute} call is one tick. */
public class TestSnapshotsExpiringExecutor extends SnapshotExpiryTestBase {

  private static final long TABLE_ID = 1L;
  private static final long OTHER_TABLE_ID = 2L;
  private static final String M2 =
      "The previous run did not finish. The expiry may have been applied; check the table's"
          + " snapshot count. The next scheduled run continues normally.";

  /** 5 seconds after a 10:00 fire, which every cron used here matches. */
  private static final Instant JUST_AFTER_TEN =
      LocalDateTime.of(2026, 1, 5, 10, 0, 5).atZone(ZoneId.systemDefault()).toInstant();

  private TestClock clock;
  private Telemetry telemetry;
  private TableService tableService;
  private Map<Long, TableConfiguration> configurations;
  private Function<TableConfiguration, SnapshotExpirySummary> attempt;
  private List<TableConfiguration> attemptConfigurations;
  private ExpiryExecutor executor;

  @Before
  public void setUp() {
    clock = new TestClock(JUST_AFTER_TEN);
    telemetry = mock(Telemetry.class);
    tableService = mock(TableService.class);
    when(tableService.contains(any())).thenReturn(true);
    when(tableService.loadTable(any())).thenAnswer(invocation -> mock(AmoroTable.class));
    configurations = new HashMap<>();
    attemptConfigurations = new ArrayList<>();
    attempt = configuration -> summary(10, 7);
  }

  @Test
  public void runsWhenTheCronFired() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("*/2 * * * *", "7d", "3"));

    executor().execute(table);

    Assert.assertEquals(1, runs(TABLE_ID).size());
    Assert.assertEquals("SUCCESS", runs(TABLE_ID).get(0).get("status"));
  }

  @Test
  public void onlyIcebergTablesAreHandled() {
    executor();
    Assert.assertTrue(executor.formatSupported(TableFormat.ICEBERG));
    Assert.assertFalse(executor.formatSupported(TableFormat.MIXED_ICEBERG));
    Assert.assertFalse(executor.formatSupported(TableFormat.MIXED_HIVE));
  }

  @Test
  public void disabledEmptyCronOrGcOffNeverRuns() throws Exception {
    TableRuntime disabled =
        table(
            TABLE_ID,
            ImmutableMap.of(
                "snapshot-expire.enabled", "false", "snapshot-expire.trigger.cron", "* * * * *"));
    TableRuntime gcOff =
        table(
            OTHER_TABLE_ID,
            ImmutableMap.of(
                "snapshot-expire.enabled",
                "true",
                "snapshot-expire.trigger.cron",
                "* * * * *",
                "gc.enabled",
                "false"));
    TableRuntime emptyCron = table(3L, ImmutableMap.of("snapshot-expire.enabled", "true"));

    executor();
    Assert.assertFalse(executor.enabled(disabled));
    Assert.assertFalse(executor.enabled(gcOff));
    executor.execute(emptyCron);

    Assert.assertTrue(runs(3L).isEmpty());
    Assert.assertTrue(attemptConfigurations.isEmpty());
  }

  @Test
  public void recordsRunningThenSuccessWithSummary() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "7d", "3"));
    List<Object> statusDuringRun = new ArrayList<>();
    attempt =
        configuration -> {
          statusDuringRun.add(singleRunStatus(TABLE_ID));
          return summary(10, 7);
        };

    executor().execute(table);

    Assert.assertEquals(Lists.newArrayList("RUNNING"), statusDuringRun);
    Map<String, Object> run = runs(TABLE_ID).get(0);
    Assert.assertEquals("SUCCESS", run.get("status"));
    Map<String, Object> summary = JacksonUtil.parseObject((String) run.get("summary"), Map.class);
    Assert.assertEquals("10", String.valueOf(summary.get("snapshotsBefore")));
    Assert.assertEquals("7", String.valueOf(summary.get("snapshotsRetained")));
    Assert.assertEquals("3", String.valueOf(summary.get("snapshotsExpired")));
  }

  @Test
  public void runIsLoggedToItsDriverLog() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "7d", "3"));

    executor().execute(table);

    long processId = ((Number) runs(TABLE_ID).get(0).get("process_id")).longValue();
    DefaultTableRuntime runtime = (DefaultTableRuntime) table;
    verify(runtime).appendDriverLogEntry(eq(processId), eq("INFO"), contains("started"), isNull());
    verify(runtime)
        .appendDriverLogEntry(
            eq(processId), eq("INFO"), contains("expired 3 of 10 snapshots"), isNull());
  }

  @Test
  public void failedAttemptsAreLoggedToTheDriverLog() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "7d", "3"));
    attempt =
        configuration -> {
          throw new CommitFailedException("conflict");
        };

    executor().execute(table);

    long processId = ((Number) runs(TABLE_ID).get(0).get("process_id")).longValue();
    DefaultTableRuntime runtime = (DefaultTableRuntime) table;
    verify(runtime, times(3))
        .appendDriverLogEntry(
            eq(processId), eq("WARN"), contains("conflict"), any(CommitFailedException.class));
    verify(runtime)
        .appendDriverLogEntry(
            eq(processId), eq("ERROR"), contains("3 attempts"), any(CommitFailedException.class));
  }

  @Test
  public void invalidConfigFailsWithoutRetryOrExpiry() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "abc", "3"));

    executor().execute(table);

    Map<String, Object> run = runs(TABLE_ID).get(0);
    Assert.assertEquals("FAILED", run.get("status"));
    Assert.assertEquals("invalid snapshot.keep.duration 'abc'", run.get("fail_message"));
    Assert.assertEquals(0, run.get("retry_number"));
    Assert.assertTrue("no expiry attempt", attemptConfigurations.isEmpty());
  }

  @Test
  public void retriesThenSucceeds() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "7d", "3"));
    attempt = failTimesThenSucceed(2);

    executor().execute(table);

    Map<String, Object> run = runs(TABLE_ID).get(0);
    Assert.assertEquals("SUCCESS", run.get("status"));
    Assert.assertEquals(2, run.get("retry_number"));
  }

  @Test
  public void failsAfterThreeAttemptsWithTheLastError() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "7d", "3"));
    int[] attempts = {0};
    attempt =
        configuration -> {
          attempts[0]++;
          throw new CommitFailedException("conflict " + attempts[0]);
        };

    executor().execute(table);

    Map<String, Object> run = runs(TABLE_ID).get(0);
    Assert.assertEquals("FAILED", run.get("status"));
    Assert.assertEquals(2, run.get("retry_number"));
    Assert.assertTrue(String.valueOf(run.get("fail_message")).contains("conflict 3"));
    Assert.assertEquals(3, attempts[0]);
  }

  @Test
  public void leftoverRunningRowIsClosedBeforeTheNextRun() throws Exception {
    long leftover = 100L;
    insertRun(leftover, TABLE_ID, "RUNNING", 0, clock.millis() - 3_600_000L, null, null);
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "7d", "3"));

    executor().execute(table);

    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(2, runs.size());
    Assert.assertEquals(leftover, ((Number) runs.get(0).get("process_id")).longValue());
    Assert.assertEquals("FAILED", runs.get(0).get("status"));
    Assert.assertEquals(M2, runs.get(0).get("fail_message"));
    Assert.assertEquals("SUCCESS", runs.get(1).get("status"));
  }

  @Test
  public void fireDuringALongRunIsSkipped() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("0,1 10 * * *", "7d", "3"));
    attempt =
        configuration -> {
          clock.advanceSeconds(150);
          return summary(10, 7);
        };

    executor().execute(table); // 10:00:05, the run ends at 10:02:35
    executor.execute(table);

    Assert.assertEquals(
        "the 10:01 fire during the run is more than 60 s old at the next tick, as for compaction",
        1,
        runs(TABLE_ID).size());
  }

  @Test
  public void configChangeDuringARunAppliesFromTheNextRun() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "7d", "3"));
    attempt =
        configuration -> {
          configurations.put(
              TABLE_ID,
              TableConfigurations.parseTableConfig(
                  ImmutableMap.of(
                      "snapshot-expire.enabled",
                      "false",
                      "snapshot-expire.trigger.cron",
                      "* * * * *",
                      "snapshot.keep.min-count",
                      "5")));
          return summary(10, 7);
        };

    executor().execute(table);

    Assert.assertEquals("SUCCESS", runs(TABLE_ID).get(0).get("status"));
    Assert.assertEquals(3, attemptConfigurations.get(0).getSnapshotMinCount());
    Assert.assertFalse("no further run is scheduled", executor.enabled(table));
  }

  @Test
  public void sendsOneCompletedEventPerRun() throws Exception {
    TableRuntime success = table(TABLE_ID, enabled("* * * * *", "7d", "3"));
    executor().execute(success);
    verify(telemetry)
        .trackSnapshotExpiryCompleted(
            eq("SUCCESS"), anyLong(), eq(1), eq(3), eq(10), eq(7), eq(10080L), eq(3), anyLong());

    TableRuntime invalid = table(OTHER_TABLE_ID, enabled("* * * * *", "abc", "3"));
    executor.execute(invalid);
    verify(telemetry)
        .trackSnapshotExpiryCompleted(
            eq("FAILED"), anyLong(), anyInt(), eq(0), eq(0), eq(0), anyLong(), anyInt(), anyLong());
    TableRuntime failing = table(3L, enabled("* * * * *", "7d", "3"));
    attempt =
        configuration -> {
          throw new CommitFailedException("conflict");
        };
    executor.execute(failing);
    verify(telemetry)
        .trackSnapshotExpiryCompleted(
            eq("FAILED"), anyLong(), eq(3), eq(0), eq(0), eq(0), anyLong(), anyInt(), anyLong());
    verify(telemetry, never()).trackFailure(any(), anyString(), any());
  }

  @Test
  public void reenablingDoesNotRunForAFireMissedWhileDisabled() throws Exception {
    // Config changes would schedule real ticks; the test drives the ticks itself.
    when(tableService.contains(any())).thenReturn(false);
    TableRuntime table = table(TABLE_ID, enabled("0 10 * * *", "7d", "3"));
    executor().execute(table);
    Assert.assertEquals("the 10:00 fire runs", 1, runs(TABLE_ID).size());

    TableConfiguration enabledConfig = configurations.get(TABLE_ID);
    configurations.put(
        TABLE_ID,
        TableConfigurations.parseTableConfig(
            ImmutableMap.of(
                "snapshot-expire.enabled", "false", "snapshot-expire.trigger.cron", "0 10 * * *")));
    executor.handleConfigChanged(table, enabledConfig);
    // Two days later, at 15:00, expiry is turned back on: the 10:00 fires in between were missed.
    clock.advanceSeconds(TimeUnit.DAYS.toSeconds(2) + TimeUnit.HOURS.toSeconds(5));
    TableConfiguration disabledConfig = configurations.get(TABLE_ID);
    configurations.put(TABLE_ID, enabledConfig);
    executor.handleConfigChanged(table, disabledConfig);
    executor.execute(table);

    Assert.assertEquals("missed fires are not caught up", 1, runs(TABLE_ID).size());
  }

  private ExpiryExecutor executor() {
    executor = new ExpiryExecutor(tableService, clock, telemetry);
    return executor;
  }

  private TableRuntime table(long tableId, Map<String, String> properties) {
    configurations.put(tableId, TableConfigurations.parseTableConfig(properties));
    TableRuntime runtime = mock(DefaultTableRuntime.class);
    when(runtime.getTableIdentifier())
        .thenReturn(
            ServerTableIdentifier.of(
                tableId, "catalog", "db", "table" + tableId, TableFormat.ICEBERG));
    when(runtime.getTableConfiguration()).thenAnswer(invocation -> configurations.get(tableId));
    return runtime;
  }

  private static Map<String, String> enabled(String cron, String duration, String minCount) {
    return ImmutableMap.of(
        "snapshot-expire.enabled",
        "true",
        "snapshot-expire.trigger.cron",
        cron,
        "snapshot.keep.duration",
        duration,
        "snapshot.keep.min-count",
        minCount);
  }

  private Function<TableConfiguration, SnapshotExpirySummary> failTimesThenSucceed(int failures) {
    int[] calls = {0};
    return configuration -> {
      if (calls[0]++ < failures) {
        throw new CommitFailedException("conflict " + calls[0]);
      }
      return summary(10, 7);
    };
  }

  private static SnapshotExpirySummary summary(int before, int retained) {
    List<Snapshot> expired = new ArrayList<>();
    for (int i = 0; i < before - retained; i++) {
      Snapshot snapshot = mock(Snapshot.class);
      when(snapshot.snapshotId()).thenReturn((long) i + 1);
      when(snapshot.timestampMillis()).thenReturn(1_000L * (i + 1));
      expired.add(snapshot);
    }
    return new SnapshotExpirySummary(before, retained, expired);
  }

  private static Object singleRunStatus(long tableId) {
    try {
      List<Map<String, Object>> runs = runs(tableId);
      return runs.size() == 1 ? runs.get(0).get("status") : runs;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** The executor with each expiry attempt replaced by {@link #attempt}. */
  private class ExpiryExecutor extends SnapshotsExpiringExecutor {
    ExpiryExecutor(TableService tableService, Clock clock, Telemetry telemetry) {
      super(tableService, 1, clock, telemetry);
    }

    @Override
    protected SnapshotExpirySummary expireOnce(
        AmoroTable<?> table, TableRuntime tableRuntime, TableConfiguration configuration) {
      attemptConfigurations.add(configuration);
      return attempt.apply(configuration);
    }
  }

  private static class TestClock extends Clock {
    private Instant now;

    TestClock(Instant now) {
      this.now = now;
    }

    void advanceSeconds(long seconds) {
      now = now.plusSeconds(seconds);
    }

    void advanceMillis(long millis) {
      now = now.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.systemDefault();
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
