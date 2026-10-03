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

package org.apache.amoro.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.amoro.it.support.FusionStack;
import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.TableLayout;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Nothing to do: a cron tick on a table that has not changed since the last successful run does not
 * rewrite files, and the API records a SKIPPED process.
 */
class NothingToDoIT extends OptimizationTestBase {

  @Test
  void unchangedTableIsSkippedWithoutRewrite() {
    String name = IcebergFixture.uniqueName("it_noop");
    Table table = iceberg.createTable(name, Layout.UNPARTITIONED, optimizationProperties());
    for (int i = 0; i < 6; i++) {
      appendFiles(table, Layout.UNPARTITIONED, FRAGMENT_ROWS);
    }

    JsonNode first = runOptimization(table, name, "MINOR");
    assertSucceededAs(first, "MINOR");

    TableLayout optimized = IcebergFixture.layout(table);
    List<String> rows = IcebergFixture.readRows(table);
    Set<String> known = FusionStack.processIds(name);

    // The minor cron keeps firing every minute; with no new commits each tick must be a skip.
    JsonNode skip =
        FusionStack.awaitProcess(
            name,
            known,
            "a SKIPPED process after the successful minor run",
            Duration.ofMinutes(4),
            p -> FusionStack.TERMINAL_STATUSES.contains(FusionStack.status(p)));
    assertEquals("SKIPPED", FusionStack.status(skip), FusionStack.describe(skip));
    assertEquals("MINOR", FusionStack.type(skip), FusionStack.describe(skip));

    TableLayout afterSkip = IcebergFixture.layout(table);
    assertEquals(optimized.snapshotId(), afterSkip.snapshotId(), "no new snapshot");
    assertEquals(optimized.dataFilePaths(), afterSkip.dataFilePaths(), "no file rewritten");
    assertEquals(rows, IcebergFixture.readRows(table), "row set unchanged");
  }
}
