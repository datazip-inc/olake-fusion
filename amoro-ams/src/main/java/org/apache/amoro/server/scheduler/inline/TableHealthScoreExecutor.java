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

import org.apache.amoro.TableRuntime;
import org.apache.amoro.optimizing.plan.AbstractOptimizingEvaluator;
import org.apache.amoro.server.persistence.TableOptimizingConfigurationsMeta;
import org.apache.amoro.server.scheduler.PeriodicTableScheduler;
import org.apache.amoro.server.table.DefaultTableRuntime;
import org.apache.amoro.server.table.TableConfigurationsService;
import org.apache.amoro.server.table.TableService;
import org.apache.amoro.server.utils.IcebergTableUtil;
import org.apache.amoro.table.BasicTableSnapshot;
import org.apache.amoro.table.MixedTable;

import java.util.Objects;

/**
 * Independently calculates the health score of iceberg tables on its own schedule. A failure here
 * cannot affect planning, evaluation or any other process or work.
 *
 * <p>Keeps track of the last snapshot_id it calculated the health score upon.
 */
public class TableHealthScoreExecutor extends PeriodicTableScheduler {

  private final long interval;

  protected TableHealthScoreExecutor(TableService tableService, int poolSize, long interval) {
    super(tableService, poolSize);
    this.interval = interval;
  }

  @Override
  protected long getNextExecutingTime(TableRuntime tableRuntime) {
    return interval;
  }

  // every table stays scheduled, whether its optimizing is enabled is read from the db on each run,
  // so the scheduling itself never depends on the db
  @Override
  protected boolean enabled(TableRuntime tableRuntime) {
    return true;
  }

  @Override
  protected long getExecutorDelay() {
    return interval;
  }

  @Override
  protected void execute(TableRuntime tableRuntime) {
    try {
      DefaultTableRuntime runtime = (DefaultTableRuntime) tableRuntime;
      TableConfigurationsService configurations = TableConfigurationsService.getInstance();
      TableOptimizingConfigurationsMeta stored = configurations.get(runtime.getTableIdentifier());
      if (!stored.getSelfOptimizingEnabled()) {
        return;
      }

      // the table is loaded fresh, so a snapshot committed since its last refresh is scored too
      MixedTable table = (MixedTable) loadTable(tableRuntime).originalTable();
      long snapshotId = IcebergTableUtil.getSnapshotId(table.asUnkeyedTable(), false);
      if (Objects.equals(snapshotId, stored.getHealthScoreSnapshotId())) {
        return;
      }

      AbstractOptimizingEvaluator.PendingInput pendingInput =
          IcebergTableUtil.createOptimizingEvaluator(
                  runtime, table, new BasicTableSnapshot(snapshotId), Integer.MAX_VALUE)
              .getPendingInput();
      runtime.setTableSummary(pendingInput);
      configurations.storeHealthScore(
          runtime.getTableIdentifier(), pendingInput.getHealthScore(), snapshotId);
    } catch (Throwable t) {
      logger.warn(
          "Failed to evaluate health score for table {}", tableRuntime.getTableIdentifier(), t);
    }
  }
}
