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

import org.apache.amoro.it.support.Await;
import org.apache.amoro.it.support.FusionStack;
import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.StackEnv;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

/**
 * Health score: a fragmented table scores below 100, the score rises after a successful
 * optimization, and the table summary and the overview show the same score.
 */

// note: right now as health score is not getting evaluated, this will fail with a timeout.
// will be fixed when health score is implemented.
class HealthScoreIT extends OptimizationTestBase {

  private static final Duration SCORE_UPDATE = Duration.ofMinutes(3);
  private static final int FRAGMENTS = 20;

  private String name;
  private Table table;

  /** A fragmented table (20 fragment files, no cron) that Fusion already lists. */
  @BeforeEach
  void createFragmentedTable() {
    name = IcebergFixture.uniqueName("it_health");
    table = iceberg.createTable(name, Layout.UNPARTITIONED, optimizationProperties());
    for (int i = 0; i < FRAGMENTS; i++) {
      appendFiles(table, Layout.UNPARTITIONED, FRAGMENT_ROWS);
    }
    FusionStack.awaitTableListed(StackEnv.TEST_DB, name);
  }

  @Test
  void fragmentedTableScoresLowAndRecoversAfterOptimization() {
    int fragmentedScore =
        Await.until(
            "health score of fragmented table " + name + " in [0, 100)",
            SCORE_UPDATE,
            () -> summaryScore(name),
            score -> score >= 0 && score < 100);
    awaitOverviewMatchesSummary(name);

    assertSucceededAs(runOptimization(table, name, "MINOR"), "MINOR");

    Await.until(
        "health score of " + name + " to rise above " + fragmentedScore,
        SCORE_UPDATE,
        () -> summaryScore(name),
        score -> score > fragmentedScore);
    awaitOverviewMatchesSummary(name);
  }

  private static int summaryScore(String table) {
    return FusionStack.client()
        .tableSummary(StackEnv.CATALOG, StackEnv.TEST_DB, table)
        .path("healthScore")
        .asInt(-1);
  }

  private static int overviewScore(String table) {
    String fullName = StackEnv.CATALOG + "." + StackEnv.TEST_DB + "." + table;
    for (JsonNode item : FusionStack.client().overviewTables()) {
      if (fullName.equals(item.path("tableName").asText())) {
        return item.path("healthScore").asInt(-1);
      }
    }
    return Integer.MIN_VALUE;
  }

  /** The overview is a cache refreshed on its own interval, so poll until it catches up. */
  private static void awaitOverviewMatchesSummary(String table) {
    Await.until(
        "overview health score of " + table + " to equal the table summary score",
        SCORE_UPDATE,
        () -> Arrays.asList(summaryScore(table), overviewScore(table)),
        scores -> scores.get(0) >= 0 && scores.get(0).equals(scores.get(1)));
  }
}
