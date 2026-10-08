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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.TableLayout;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

/**
 * Priority between optimizing types when several crons fire in the same minute.
 *
 * <p>Two places decide it: the refresh tick walks FULL, MAJOR, MINOR and marks the table pending
 * with the first type whose cron fired (TableRuntimeRefreshExecutor.evaluateCronTriggers), and the
 * planner gives each partition its own type and labels the process with the highest one any planned
 * partition has (AbstractOptimizingPlanner, "any partition wins").
 */
class CronPriorityIT extends OptimizationTestBase {

  private static final String EU = "eu";
  private static final String US = "us";

  /**
   * Per partition: 2 undersized segment files and 3 fragment files, no deletes. That qualifies for
   * FULL (fragmentFileCount >= 2), MAJOR (dataFileCount > 1) and MINOR (small files with the minor
   * cron fired) at once, so only priority decides the type.
   */
  private void writeLayoutEligibleForAllTypes(Table table, Layout layout) {
    appendFiles(table, layout, UNDERSIZED_SEGMENT_ROWS);
    appendFiles(table, layout, UNDERSIZED_SEGMENT_ROWS);
    for (int i = 0; i < 3; i++) {
      appendFiles(table, layout, FRAGMENT_ROWS);
    }
  }

  private void runAndExpect(Layout layout, String prefix, String expected, String... crons) {
    String name = IcebergFixture.uniqueName(prefix + "_" + layout.name().toLowerCase());
    Table table = iceberg.createTable(name, layout, optimizationProperties());
    writeLayoutEligibleForAllTypes(table, layout);
    List<String> rows = IcebergFixture.readRows(table);

    JsonNode process = runOptimizationWithCrons(table, name, crons);
    assertSucceededAs(process, expected);
    assertEquals(rows, IcebergFixture.readRows(table), "row set after optimization");
  }

  /** FULL beats MAJOR and MINOR. */
  @ParameterizedTest
  @EnumSource(Layout.class)
  void fullBeatsMajorAndMinor(Layout layout) {
    runAndExpect(layout, "it_prio_fmm", "FULL", "FULL", "MAJOR", "MINOR");
  }

  /** MAJOR beats MINOR. */
  @ParameterizedTest
  @EnumSource(Layout.class)
  void majorBeatsMinor(Layout layout) {
    runAndExpect(layout, "it_prio_mm", "MAJOR", "MAJOR", "MINOR");
  }

  /** FULL beats MINOR with no MAJOR cron in between. */
  @ParameterizedTest
  @EnumSource(Layout.class)
  void fullBeatsMinor(Layout layout) {
    runAndExpect(layout, "it_prio_fm", "FULL", "FULL", "MINOR");
  }

  /**
   * eu qualifies for FULL (3 fragments), us only for MAJOR (1 fragment + 1 undersized segment:
   * fragmentFileCount and undersizedSegmentFileCount are 1, dataFileCount is 2). The process is
   * FULL because one planned partition is FULL.
   */
  @Test
  void anyFullPartitionMakesProcessFull() {
    String name = IcebergFixture.uniqueName("it_prio_part_full");
    Table table = iceberg.createTable(name, Layout.PARTITIONED, optimizationProperties());
    for (int i = 0; i < 3; i++) {
      appendToRegion(table, EU, FRAGMENT_ROWS);
    }
    appendToRegion(table, US, FRAGMENT_ROWS);
    appendToRegion(table, US, UNDERSIZED_SEGMENT_ROWS);
    List<String> rows = IcebergFixture.readRows(table);
    TableLayout before = IcebergFixture.layout(table);

    JsonNode process = runOptimizationWithCrons(table, name, "FULL", "MAJOR");
    assertSucceededAs(process, "FULL");

    TableLayout after = IcebergFixture.layout(table);
    assertTrue(
        dataFilesIn(after, EU) < dataFilesIn(before, EU),
        "eu fragments merged; before=" + before + " after=" + after);
    assertEquals(rows, IcebergFixture.readRows(table), "row set after optimization");
  }

  /**
   * eu qualifies for MAJOR (4 undersized segments, no fragments), us only for MINOR (1 fragment:
   * dataFileCount is 1, small files with the minor cron fired). The process is MAJOR.
   */
  @Test
  void anyMajorPartitionMakesProcessMajor() {
    String name = IcebergFixture.uniqueName("it_prio_part_major");
    Table table = iceberg.createTable(name, Layout.PARTITIONED, optimizationProperties());
    for (int i = 0; i < 4; i++) {
      appendToRegion(table, EU, UNDERSIZED_SEGMENT_ROWS);
    }
    appendToRegion(table, US, FRAGMENT_ROWS);
    List<String> rows = IcebergFixture.readRows(table);
    TableLayout before = IcebergFixture.layout(table);

    JsonNode process = runOptimizationWithCrons(table, name, "MAJOR", "MINOR");
    assertSucceededAs(process, "MAJOR");

    TableLayout after = IcebergFixture.layout(table);
    assertTrue(
        dataFilesIn(after, EU) < dataFilesIn(before, EU),
        "eu segments merged; before=" + before + " after=" + after);
    assertEquals(rows, IcebergFixture.readRows(table), "row set after optimization");
  }
}
