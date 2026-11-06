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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.amoro.ServerTableIdentifier;
import org.apache.amoro.TableFormat;
import org.apache.amoro.TableRuntime;
import org.apache.amoro.server.persistence.SnapshotExpiryTestBase;
import org.apache.amoro.server.table.TableService;
import org.apache.amoro.server.utils.SnowflakeIdGenerator;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** {@link OptimizingExpiringExecutor} also cleans {@code snapshot_expiry_process}. */
public class TestSnapshotExpiryHistoryCleanup extends SnapshotExpiryTestBase {

  private static final long TABLE_ID = 7L;

  @Test
  public void historyCleanupDeletesRowsOlderThanKeepDays() throws Exception {
    long now = System.currentTimeMillis();
    long thirtyOneDaysAgo = now - TimeUnit.DAYS.toMillis(31);
    long twentyNineDaysAgo = now - TimeUnit.DAYS.toMillis(29);
    long oldRun = SnowflakeIdGenerator.getMinSnowflakeId(thirtyOneDaysAgo);
    long recentRun = SnowflakeIdGenerator.getMinSnowflakeId(twentyNineDaysAgo);
    insertRun(oldRun, TABLE_ID, "SUCCESS", 0, thirtyOneDaysAgo, null, "{}");
    insertRun(recentRun, TABLE_ID, "SUCCESS", 0, twentyNineDaysAgo, null, "{}");
    TableService tableService = mock(TableService.class);
    when(tableService.contains(any())).thenReturn(true);
    TableRuntime table = mock(TableRuntime.class);
    when(table.getTableIdentifier())
        .thenReturn(ServerTableIdentifier.of(TABLE_ID, "catalog", "db", "t", TableFormat.ICEBERG));

    new OptimizingExpiringExecutor(tableService, 30, 1).execute(table);

    List<Map<String, Object>> runs = runs(TABLE_ID);
    Assert.assertEquals(1, runs.size());
    Assert.assertEquals(recentRun, ((Number) runs.get(0).get("process_id")).longValue());
  }
}
