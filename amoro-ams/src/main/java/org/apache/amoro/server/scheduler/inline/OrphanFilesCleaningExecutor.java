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

import org.apache.amoro.AmoroTable;
import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.TableFormat;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.config.TableConfiguration;
import org.apache.amoro.formats.iceberg.maintainer.IcebergTableMaintainer;
import org.apache.amoro.formats.iceberg.maintainer.OrphanFilesCleaningSummary;
import org.apache.amoro.process.ProcessStatus;
import org.apache.amoro.server.optimizing.maintainer.DefaultTableMaintainerContext;
import org.apache.amoro.server.persistence.PersistentBase;
import org.apache.amoro.server.persistence.mapper.OrphanFilesCleaningProcessMapper;
import org.apache.amoro.server.scheduler.PeriodicTableScheduler;
import org.apache.amoro.server.table.DefaultTableRuntime;
import org.apache.amoro.server.table.TableService;
import org.apache.amoro.server.utils.SnowflakeIdGenerator;
import org.apache.amoro.server.utils.Telemetry;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.table.TableProperties;
import org.apache.amoro.utils.CronUtils;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotSummary;
import org.apache.iceberg.Table;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cleans the orphan data and metadata files of a table when its {@code clean-orphan-files.trigger.cron}
 * fires, checked every minute, and records each run as one row of {@code orphan_files_cleaning_process}.
 * Runs never touch compaction state.
 */
public class OrphanFilesCleaningExecutor extends PeriodicTableScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(OrphanFilesCleaningExecutor.class);

  static final int MAX_ATTEMPTS = 3;
  static final String RESTARTED_MESSAGE =
      "Server restarted during the run. The cleaning may have been applied; check the table's"
          + " files. The next scheduled run continues normally.";
  static final String UNFINISHED_MESSAGE =
      "The previous run did not finish. The cleaning may have been applied; check the table's"
          + " files. The next scheduled run continues normally.";

  private static final long TICK_MILLIS = TimeUnit.MINUTES.toMillis(1);
  /** Same cron window as the compaction and snapshot expiry cron check. */
  private static final long CRON_WINDOW_SECONDS = 60;
  /** The {@code fail_message} column holds at most this many characters. */
  private static final int MAX_FAIL_MESSAGE_LENGTH = 4096;

  /** Highest Iceberg table format version orphan files cleaning runs on; v3 tables are rejected. */
  static final int MAX_FORMAT_VERSION = 2;

  private static final SnowflakeIdGenerator ID_GENERATOR = new SnowflakeIdGenerator();

  private final Clock clock;
  private final Telemetry telemetry;
  private final Persistency persistency = new Persistency();

  public OrphanFilesCleaningExecutor(
      TableService tableService, int poolSize, Clock clock, Telemetry telemetry) {
    super(tableService, poolSize);
    this.clock = clock;
    this.telemetry = telemetry;
  }

  public OrphanFilesCleaningExecutor(TableService tableService, int poolSize, Duration interval) {
    this(tableService, poolSize, Clock.systemDefaultZone(), Telemetry.getInstance());
  }

  @Override
  protected void initHandler(List<TableRuntime> tableRuntimeList) {
    int closed = persistency.failRunning(null, RESTARTED_MESSAGE, clock.millis());
    if (closed > 0) {
      LOG.warn("Marked {} orphan files cleaning runs left running by a restart as failed", closed);
    }
    super.initHandler(tableRuntimeList);
  }

  @Override
  protected long getNextExecutingTime(TableRuntime tableRuntime) {
    return TICK_MILLIS;
  }

  /**
   * Iceberg only; other formats are never scheduled, as compaction skips formats it cannot handle.
   */
  @Override
  protected boolean formatSupported(TableFormat format) {
    return TableFormat.ICEBERG.equals(format);
  }

  @Override
  protected boolean enabled(TableRuntime tableRuntime) {
    return tableRuntime.getTableConfiguration().isCleanOrphanEnabled();
  }

  @Override
  public void handleConfigChanged(TableRuntime tableRuntime, TableConfiguration originalConfig) {
    scheduleIfNecessary(tableRuntime, getStartDelay());
  }

  @Override
  protected long getExecutorDelay() {
    return 0;
  }

  @Override
  public void execute(TableRuntime tableRuntime) {
    // Read once: a change made during the run applies from the next run.
    TableConfiguration configuration = tableRuntime.getTableConfiguration();
    long now = clock.millis();
    if (!CronUtils.hasFiredInWindow(
        configuration.getCleanOrphanTriggerCron(), now, CRON_WINDOW_SECONDS)) {
      return;
    }
    try {
      run(tableRuntime, configuration, now);
    } catch (Throwable t) {
      LOG.error(
          "Orphan files cleaning of table {} could not be recorded",
          tableRuntime.getTableIdentifier(),
          t);
    }
  }

  /**
   * One orphan cleaning attempt on {@code table} with the configuration read when the run started.
   *
   * @throws UnsupportedOperationException if the table's format version is above {@link
   *     #MAX_FORMAT_VERSION}
   */
  protected OrphanFilesCleaningSummary cleanOnce(
      AmoroTable<?> table, TableRuntime tableRuntime, TableConfiguration configuration) {
    Preconditions.checkArgument(tableRuntime instanceof DefaultTableRuntime);
    Table icebergTable = (Table) table.originalTable();
    checkFormatVersionSupported(icebergTable);
    IcebergTableMaintainer maintainer =
        new IcebergTableMaintainer(
            icebergTable,
            table.id(),
            new DefaultTableMaintainerContext((DefaultTableRuntime) tableRuntime) {
              @Override
              public TableConfiguration getTableConfiguration() {
                return configuration;
              }
            });
    long keepTime = configuration.getOrphanExistingMinutes() * 60 * 1000L;
    return maintainer.cleanOrphanFilesWithSummary(clock.millis() - keepTime);
  }

  /** Throws when the table's format version is above {@link #MAX_FORMAT_VERSION}. */
  private static void checkFormatVersionSupported(Table table) {
    int formatVersion = ((HasTableOperations) table).operations().current().formatVersion();
    if (formatVersion > MAX_FORMAT_VERSION) {
      throw new UnsupportedOperationException(
          String.format(
              "Orphan files cleaning does not support Iceberg format version %d tables",
              formatVersion));
    }
  }

  private void run(TableRuntime tableRuntime, TableConfiguration configuration, long startTime) {
    ServerTableIdentifier identifier = tableRuntime.getTableIdentifier();
    persistency.failRunning(identifier.getId(), UNFINISHED_MESSAGE, startTime);
    long processId = ID_GENERATOR.generateId();
    runLog(
        tableRuntime,
        processId,
        "INFO",
        String.format(
            "Orphan files cleaning started: cron '%s', %s %d minutes",
            configuration.getCleanOrphanTriggerCron(),
            TableProperties.MIN_ORPHAN_FILE_EXISTING_TIME,
            configuration.getOrphanExistingMinutes()),
        null);
    String configError = configuration.getCleanOrphanConfigError();
    if (configError != null) {
      persistency.insert(
          processId,
          identifier.getId(),
          ProcessStatus.FAILED,
          startTime,
          clock.millis(),
          configError);
      LOG.warn("Orphan files cleaning of table {} failed: {}", identifier, configError);
      runLog(tableRuntime, processId, "ERROR", "Orphan files cleaning failed: " + configError, null);
      trackCompleted(ProcessStatus.FAILED, startTime, 0, null, configuration, 0);
      return;
    }
    persistency.insert(processId, identifier.getId(), ProcessStatus.RUNNING, startTime, 0, null);

    OrphanFilesCleaningSummary summary = null;
    Exception lastError = null;
    long tableSize = 0;
    int attempts = 0;
    while (summary == null && attempts < MAX_ATTEMPTS) {
      attempts++;
      try {
        AmoroTable<?> table = loadTable(tableRuntime);
        tableSize = tableSizeBytes(table);
        summary = cleanOnce(table, tableRuntime, configuration);
      } catch (UnsupportedOperationException e) {
        // An unsupported table fails the same way on every attempt: fail the run at once.
        lastError = e;
        LOG.warn(
            "Orphan files cleaning of table {} is not supported: {}", identifier, e.getMessage());
        break;
      } catch (Exception e) {
        // Any other failure of an attempt is retried; the last one is recorded on the run.
        lastError = e;
        LOG.warn(
            "Orphan files cleaning attempt {} of {} failed for table {}: {}",
            attempts,
            MAX_ATTEMPTS,
            identifier,
            e.toString());
        runLog(
            tableRuntime,
            processId,
            "WARN",
            String.format("Attempt %d of %d failed: %s", attempts, MAX_ATTEMPTS, e),
            e);
      }
    }

    if (summary != null) {
      persistency.finish(
          processId, ProcessStatus.SUCCESS, attempts - 1, clock.millis(), null, summaryOf(summary));
      String cleaned = cleanedMessage(summary);
      LOG.info("Orphan files cleaning of table {} {}", identifier, cleaned);
      runLog(tableRuntime, processId, "INFO", "Orphan files cleaning " + cleaned, null);
      trackCompleted(ProcessStatus.SUCCESS, startTime, attempts, summary, configuration, tableSize);
    } else {
      persistency.finish(
          processId,
          ProcessStatus.FAILED,
          attempts - 1,
          clock.millis(),
          failMessageOf(lastError),
          null);
      LOG.error(
          "Orphan files cleaning of table {} failed after {} attempts",
          identifier,
          attempts,
          lastError);
      runLog(
          tableRuntime,
          processId,
          "ERROR",
          String.format("Orphan files cleaning failed after %d attempts: %s", attempts, lastError),
          lastError);
      trackCompleted(ProcessStatus.FAILED, startTime, attempts, null, configuration, tableSize);
    }
  }

  private void trackCompleted(
      ProcessStatus status,
      long startTime,
      int attempts,
      OrphanFilesCleaningSummary summary,
      TableConfiguration configuration,
      long tableSize) {
    telemetry.trackOrphanFilesCleaningCompleted(
        status.name(),
        clock.millis() - startTime,
        attempts,
        summary == null ? 0 : summary.orphanDataFilesDeleted(),
        summary == null ? 0 : summary.orphanMetadataFilesDeleted(),
        configuration.getOrphanExistingMinutes(),
        tableSize);
  }

  private static long tableSizeBytes(AmoroTable<?> table) {
    if (!(table.originalTable() instanceof Table)) {
      return 0;
    }
    Snapshot current = ((Table) table.originalTable()).currentSnapshot();
    return current == null
        ? 0
        : PropertyUtil.propertyAsLong(current.summary(), SnapshotSummary.TOTAL_FILE_SIZE_PROP, 0);
  }

  private static Map<String, String> summaryOf(OrphanFilesCleaningSummary summary) {
    Map<String, String> values = new HashMap<>();
    values.put("dataFilesExpected", String.valueOf(summary.orphanDataFilesExpected()));
    values.put("dataFilesDeleted", String.valueOf(summary.orphanDataFilesDeleted()));
    values.put("metadataFilesExpected", String.valueOf(summary.orphanMetadataFilesExpected()));
    values.put("metadataFilesDeleted", String.valueOf(summary.orphanMetadataFilesDeleted()));
    values.put("totalFilesDeleted", String.valueOf(summary.totalDeleted()));
    return values;
  }

  private static String failMessageOf(Exception error) {
    if (error == null) {
      return "Unknown error";
    }
    String message = error.getClass().getSimpleName() + ": " + error.getMessage();
    return message.length() <= MAX_FAIL_MESSAGE_LENGTH
        ? message
        : message.substring(0, MAX_FAIL_MESSAGE_LENGTH);
  }

  private static String cleanedMessage(OrphanFilesCleaningSummary summary) {
    return String.format(
        "deleted %d orphan files (%d data files, %d metadata files)",
        summary.totalDeleted(),
        summary.orphanDataFilesDeleted(),
        summary.orphanMetadataFilesDeleted());
  }

  /**
   * One entry in the run's {@code driver.log}, which OLake-UI's run logs read, as compaction writes
   * its failures from AMS.
   */
  private static void runLog(
      TableRuntime tableRuntime, long processId, String level, String message, Throwable error) {
    if (tableRuntime instanceof DefaultTableRuntime) {
      ((DefaultTableRuntime) tableRuntime).appendDriverLogEntry(processId, level, message, error);
    }
  }

  private static class Persistency extends PersistentBase {

    int failRunning(Long tableId, String failMessage, long finishTime) {
      int[] closed = {0};
      doAs(
          OrphanFilesCleaningProcessMapper.class,
          mapper -> closed[0] = mapper.failRunning(tableId, failMessage, finishTime));
      return closed[0];
    }

    void insert(
        long processId,
        long tableId,
        ProcessStatus status,
        long createTime,
        long finishTime,
        String failMessage) {
      doAs(
          OrphanFilesCleaningProcessMapper.class,
          mapper ->
              mapper.insert(
                  processId, tableId, status, 0, createTime, finishTime, failMessage, null));
    }

    void finish(
        long processId,
        ProcessStatus status,
        int retryNumber,
        long finishTime,
        String failMessage,
        Map<String, String> summary) {
      doAs(
          OrphanFilesCleaningProcessMapper.class,
          mapper ->
              mapper.update(processId, status, retryNumber, finishTime, failMessage, summary));
    }
  }
}
