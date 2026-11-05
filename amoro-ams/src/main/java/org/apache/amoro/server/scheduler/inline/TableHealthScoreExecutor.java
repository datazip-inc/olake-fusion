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

import org.apache.amoro.TableFormat;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.iceberg.Constants;
import org.apache.amoro.optimizing.plan.AbstractOptimizingEvaluator;
import org.apache.amoro.server.scheduler.PeriodicTableScheduler;
import org.apache.amoro.server.table.DefaultTableRuntime;
import org.apache.amoro.server.table.TableService;
import org.apache.amoro.server.utils.IcebergTableUtil;
import org.apache.amoro.server.utils.Telemetry;
import org.apache.amoro.table.BasicTableSnapshot;
import org.apache.amoro.table.MixedTable;

/**
 * Calculates the health score of Iceberg tables on its own schedule and threads. A failure here
 * never affects any of the happy paths.
 *
 * <p>The score is stored in the table summary together with the snapshot it was calculated on, and
 * is calculated again only once the table's current snapshot changes.
 *
 * <p>TODO: Store timestamp when the last healthscore was calculated.
 */
public class TableHealthScoreExecutor extends PeriodicTableScheduler {

  private final long interval;

  public TableHealthScoreExecutor(TableService tableService, int poolSize, long interval) {
    super(tableService, poolSize);
    this.interval = interval;
  }

  // Iceberg tables only. Whether optimizing is enabled is checked on each run instead.
  // So a table enabled later needs no rescheduling.
  @Override
  protected boolean enabled(TableRuntime tableRuntime) {
    return tableRuntime instanceof DefaultTableRuntime
        && TableFormat.ICEBERG.equals(tableRuntime.getFormat());
  }

  @Override
  protected long getNextExecutingTime(TableRuntime tableRuntime) {
    return interval;
  }

  // first runs one interval after the table refreshes that start with Fusion
  @Override
  protected long getExecutorDelay() {
    return interval;
  }

  @Override
  public void execute(TableRuntime tableRuntime) {
    try {
      DefaultTableRuntime runtime = (DefaultTableRuntime) tableRuntime;
      if (!runtime.getOptimizingConfig().isEnabled()) {
        return;
      }

      // loaded fresh, so a snapshot committed since the table's last refresh is scored too
      MixedTable table = (MixedTable) loadTable(tableRuntime).originalTable();
      long snapshotId = IcebergTableUtil.getSnapshotId(table.asUnkeyedTable(), false);
      // nothing to score without a snapshot, nor when this snapshot is scored already
      if (snapshotId == Constants.INVALID_SNAPSHOT_ID
          || snapshotId == runtime.getHealthScoreSnapshotId()) {
        return;
      }

      // the score covers every partition, the limit only caps the ones picked for optimizing
      AbstractOptimizingEvaluator.PendingInput pendingInput =
          IcebergTableUtil.createOptimizingEvaluator(
                  runtime, table, new BasicTableSnapshot(snapshotId), Integer.MAX_VALUE)
              .getPendingInput();
      runtime.setTableSummary(pendingInput, snapshotId);
    } catch (Throwable t) {
      logger.warn(
          "Failed to calculate health score for table {}", tableRuntime.getTableIdentifier(), t);
      Telemetry.getInstance()
          .trackFailure(
              Telemetry.Command.EVALUATE_TABLE_HEALTH,
              Telemetry.errorSource(tableRuntime.getFormat()),
              t);
    }
  }
}
