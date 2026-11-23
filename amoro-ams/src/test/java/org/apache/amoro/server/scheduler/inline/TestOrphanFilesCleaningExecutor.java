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
import org.apache.amoro.formats.iceberg.maintainer.OrphanFilesCleaningSummary;
import org.apache.amoro.server.persistence.OrphanFilesCleaningTestBase;
import org.apache.amoro.server.table.DefaultTableRuntime;
import org.apache.amoro.server.table.TableConfigurations;
import org.apache.amoro.server.table.TableService;
import org.apache.amoro.server.utils.Telemetry;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.utils.JacksonUtil;
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
import java.util.function.Function;

/** {@link OrphanFilesCleaningExecutor} on a real Postgres: one {@code execute} call is one tick. */
public class TestOrphanFilesCleaningExecutor extends OrphanFilesCleaningTestBase {

  private static final long TABLE_ID = 1L;
  private static final long OTHER_TABLE_ID = 2L;
  private static final String UNFINISHED_MESSAGE =
      "The previous run did not finish. The cleaning may have been applied; check the table's"
          + " files. The next scheduled run continues normally.";

  /** 5 seconds after a 10:00 fire, which every cron used here matches. */
  private static final Instant JUST_AFTER_TEN =
      LocalDateTime.of(2026, 1, 5, 10, 0, 5).atZone(ZoneId.systemDefault()).toInstant();

  private TestClock clock;
  private Telemetry telemetry;
  private TableService tableService;
  private Map<Long, TableConfiguration> configurations;
  private Function<TableConfiguration, OrphanFilesCleaningSummary> attempt;
  private List<TableConfiguration> attemptConfigurations;
  private TestCleaningExecutor executor;

  @Before
  public void setUp() {
    clock = new TestClock(JUST_AFTER_TEN);
    telemetry = mock(Telemetry.class);
    tableService = mock(TableService.class);
    when(tableService.contains(any())).thenReturn(true);
    when(tableService.loadTable(any())).thenAnswer(invocation -> mock(AmoroTable.class));
    configurations = new HashMap<>();
    attemptConfigurations = new ArrayList<>();
    attempt = configuration -> summary(5, 5, 2, 2);
  }

  @Test
  public void runsWhenTheCronFired() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("*/2 * * * *", "120"));

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
                "clean-orphan-files.enabled", "false", "clean-orphan-files.trigger.cron", "* * * * *"));
    TableRuntime gcOff =
        table(
            OTHER_TABLE_ID,
            ImmutableMap.of(
                "clean-orphan-files.enabled",
                "true",
                "clean-orphan-files.trigger.cron",
                "* * * * *",
                "gc.enabled",
                "false"));
    TableRuntime emptyCron = table(3L, ImmutableMap.of("clean-orphan-files.enabled", "true"));

    executor();
    Assert.assertFalse(executor.enabled(disabled));
    Assert.assertFalse(executor.enabled(gcOff));
    executor.execute(emptyCron);

    Assert.assertTrue(runs(3L).isEmpty());
    Assert.assertTrue(attemptConfigurations.isEmpty());
  }

  @Test
  public void recordsRunningThenSuccessWithSummary() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "120"));
    List<Object> statusDuringRun = new ArrayList<>();
    attempt =
        configuration -> {
          statusDuringRun.add(singleRunStatus(TABLE_ID));
          return summary(10, 8, 4, 3);
        };

    executor().execute(table);

    Assert.assertEquals(Lists.newArrayList("RUNNING"), statusDuringRun);
    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(1, runs.size());
    Assert.assertEquals("SUCCESS", runs.get(0).get("status"));
    Assert.assertEquals(0, runs.get(0).get("retry_number"));
    Assert.assertNull(runs.get(0).get("fail_message"));

    Map<String, String> summary =
        JacksonUtil.parseObject((String) runs.get(0).get("summary"), Map.class);
    Assert.assertEquals("10", summary.get("dataFilesExpected"));
    Assert.assertEquals("8", summary.get("dataFilesDeleted"));
    Assert.assertEquals("4", summary.get("metadataFilesExpected"));
    Assert.assertEquals("3", summary.get("metadataFilesDeleted"));
    Assert.assertEquals("11", summary.get("totalFilesDeleted"));
  }

  @Test
  public void retriesUntilSucceeds() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "120"));
    attempt = failTimesThenSucceed(2);

    executor().execute(table);

    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(1, runs.size());
    Assert.assertEquals("SUCCESS", runs.get(0).get("status"));
    Assert.assertEquals(2, runs.get(0).get("retry_number"));
    Assert.assertNull(runs.get(0).get("fail_message"));
  }

  @Test
  public void recordsFailedWhenAllAttemptsFail() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "120"));
    attempt = failTimesThenSucceed(OrphanFilesCleaningExecutor.MAX_ATTEMPTS);

    executor().execute(table);

    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(1, runs.size());
    Assert.assertEquals("FAILED", runs.get(0).get("status"));
    Assert.assertEquals(OrphanFilesCleaningExecutor.MAX_ATTEMPTS - 1, runs.get(0).get("retry_number"));
    Assert.assertEquals(
        "CommitFailedException: conflict " + OrphanFilesCleaningExecutor.MAX_ATTEMPTS,
        runs.get(0).get("fail_message"));
    Assert.assertNull(runs.get(0).get("summary"));
  }

  @Test
  public void restartMarksRunsLeftRunningAsFailed() throws Exception {
    insertRun(101L, TABLE_ID, "RUNNING", 0, clock.millis() - 5000, null, null);
    insertRun(102L, OTHER_TABLE_ID, "RUNNING", 1, clock.millis() - 5000, null, null);

    executor().initHandler(new ArrayList<>());

    Assert.assertEquals("FAILED", runs(TABLE_ID).get(0).get("status"));
    Assert.assertEquals(
        OrphanFilesCleaningExecutor.RESTARTED_MESSAGE, runs(TABLE_ID).get(0).get("fail_message"));
    Assert.assertEquals("FAILED", runs(OTHER_TABLE_ID).get(0).get("status"));
  }

  @Test
  public void unfinishedPreviousRunMarkedAsFailedWhenNextStarts() throws Exception {
    insertRun(101L, TABLE_ID, "RUNNING", 0, clock.millis() - 5000, null, null);
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "120"));

    executor().execute(table);

    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(2, runs.size());
    Assert.assertEquals(101L, runs.get(0).get("process_id"));
    Assert.assertEquals("FAILED", runs.get(0).get("status"));
    Assert.assertEquals(UNFINISHED_MESSAGE, runs.get(0).get("fail_message"));
    Assert.assertEquals("SUCCESS", runs.get(1).get("status"));
  }

  @Test
  public void badConfigurationRecordsFailedRunWithoutAttempt() throws Exception {
    TableRuntime table =
        table(
            TABLE_ID,
            ImmutableMap.of(
                "clean-orphan-files.enabled",
                "true",
                "clean-orphan-files.trigger.cron",
                "* * * * *",
                "clean-orphan-file.min-existing-time-minutes",
                "0"));

    executor().execute(table);

    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(1, runs.size());
    Assert.assertEquals("FAILED", runs.get(0).get("status"));
    Assert.assertEquals(
        "invalid clean-orphan-file.min-existing-time-minutes '0'",
        runs.get(0).get("fail_message"));
    Assert.assertTrue(attemptConfigurations.isEmpty());
  }

  @Test
  public void v3TablesFailImmediatelyWithoutRetries() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "120"));
    attempt =
        configuration -> {
          throw new UnsupportedOperationException(
              "Orphan files cleaning does not support Iceberg format version 3 tables");
        };

    executor().execute(table);

    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(1, runs.size());
    Assert.assertEquals("FAILED", runs.get(0).get("status"));
    Assert.assertEquals(0, runs.get(0).get("retry_number"));
    Assert.assertEquals(
        "UnsupportedOperationException: Orphan files cleaning does not support Iceberg format version 3 tables",
        runs.get(0).get("fail_message"));
  }

  @Test
  public void telemetryTrackedOnSuccessAndFailure() throws Exception {
    TableRuntime table = table(TABLE_ID, enabled("* * * * *", "120"));

    executor().execute(table);
    verify(telemetry, times(1))
        .trackOrphanFilesCleaningCompleted(
            eq("SUCCESS"), anyLong(), eq(1), eq(5), eq(2), eq(120L), anyLong());

    attempt = failTimesThenSucceed(3);
    executor.execute(table);
    verify(telemetry, times(1))
        .trackOrphanFilesCleaningCompleted(
            eq("FAILED"), anyLong(), eq(3), eq(0), eq(0), eq(120L), anyLong());
  }

  private TableRuntime table(long id, Map<String, String> properties) {
    ServerTableIdentifier identifier = ServerTableIdentifier.of(id, "test_cat", "test_db", "test_tbl");
    TableConfiguration config = TableConfigurations.parseTableConfig(properties);
    configurations.put(id, config);
    DefaultTableRuntime tableRuntime = mock(DefaultTableRuntime.class);
    when(tableRuntime.getTableIdentifier()).thenReturn(identifier);
    when(tableRuntime.getTableConfiguration()).thenAnswer(inv -> configurations.get(id));
    when(tableRuntime.getFormat()).thenReturn(TableFormat.ICEBERG);
    return tableRuntime;
  }

  private static Map<String, String> enabled(String cron, String minExistingMinutes) {
    return ImmutableMap.of(
        "clean-orphan-files.enabled",
        "true",
        "clean-orphan-files.trigger.cron",
        cron,
        "clean-orphan-file.min-existing-time-minutes",
        minExistingMinutes);
  }

  private Function<TableConfiguration, OrphanFilesCleaningSummary> failTimesThenSucceed(int failures) {
    int[] calls = {0};
    return configuration -> {
      if (calls[0]++ < failures) {
        throw new CommitFailedException("conflict " + calls[0]);
      }
      return summary(5, 5, 2, 2);
    };
  }

  private static OrphanFilesCleaningSummary summary(
      int dataExpected, int dataDeleted, int metaExpected, int metaDeleted) {
    return new OrphanFilesCleaningSummary(dataExpected, dataDeleted, metaExpected, metaDeleted);
  }

  private static Object singleRunStatus(long tableId) {
    try {
      List<Map<String, Object>> runs = runs(tableId);
      return runs.size() == 1 ? runs.get(0).get("status") : runs;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private TestCleaningExecutor executor() {
    if (executor == null) {
      executor = new TestCleaningExecutor(tableService, clock, telemetry);
    }
    return executor;
  }

  private class TestCleaningExecutor extends OrphanFilesCleaningExecutor {
    TestCleaningExecutor(TableService tableService, Clock clock, Telemetry telemetry) {
      super(tableService, 1, clock, telemetry);
    }

    @Override
    protected OrphanFilesCleaningSummary cleanOnce(
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
