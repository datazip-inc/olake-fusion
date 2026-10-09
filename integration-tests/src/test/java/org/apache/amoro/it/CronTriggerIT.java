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

import org.apache.amoro.it.support.Await;
import org.apache.amoro.it.support.FusionStack;
import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.StackEnv;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * Cron triggering: a table with pending small files sits idle until its cron fires, and the cron
 * firing starts an optimizing process.
 *
 * <p>AMS checks crons on every refresh tick (15s in the IT config) and treats a cron as fired for
 * 60s after its scheduled minute, in the AMS JVM time zone (UTC in the container).
 */
class CronTriggerIT extends OptimizationTestBase {

  private static final String MINOR_CRON = "self-optimizing.minor.trigger.cron";

  /** Longest gap between a cron's scheduled minute and the process it starts. */
  private static final Duration FIRE_LATENCY = Duration.ofSeconds(90);

  private Table tableWithFragments(String name) {
    Table table = iceberg.createTable(name, Layout.UNPARTITIONED, optimizationProperties());
    for (int i = 0; i < 3; i++) {
      appendFiles(table, Layout.UNPARTITIONED, FRAGMENT_ROWS);
    }
    FusionStack.awaitTableListed(StackEnv.TEST_DB, name);
    return table;
  }

  /** 2a: no cron, no process; an every-minute cron starts one within a minute and a tick. */
  @Test
  void everyMinuteCronStartsOptimization() {
    String name = IcebergFixture.uniqueName("it_cron_every");
    Table table = tableWithFragments(name);
    Set<String> known = FusionStack.processIds(name);

    // Two refresh ticks without a cron: nothing may be scheduled.
    Await.sleep(Duration.ofSeconds(35));
    assertTrue(
        FusionStack.newProcesses(name, known).isEmpty(),
        "no process without a cron: " + FusionStack.newProcesses(name, known));

    long enabledAt = System.currentTimeMillis();
    FusionStack.enableCron(table, "MINOR");
    JsonNode process = FusionStack.awaitFinishedOptimization(name, known);

    assertSucceededAs(process, "MINOR");
    long started = process.path("startTime").asLong();
    assertTrue(
        started - enabledAt <= Duration.ofMinutes(1).plus(FIRE_LATENCY).toMillis(),
        "started within a minute of enabling the cron: " + FusionStack.describe(process));
  }

  /** 2b: a cron for a specific minute fires at that minute and not before. */
  @Test
  void scheduledCronFiresAtItsMinute() {
    String name = IcebergFixture.uniqueName("it_cron_at");
    Table table = tableWithFragments(name);
    Set<String> known = FusionStack.processIds(name);

    ZonedDateTime target =
        ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).plusMinutes(2);
    long targetMillis = target.toInstant().toEpochMilli();
    String cron = target.getMinute() + " " + target.getHour() + " * * *";
    IcebergFixture.setProperties(table, Map.of(MINOR_CRON, cron));

    // Until the scheduled minute nothing may start.
    Await.sleep(Duration.ofMillis(Math.max(0, targetMillis - System.currentTimeMillis() - 2000)));
    assertTrue(
        FusionStack.newProcesses(name, known).isEmpty(),
        "no process before "
            + target
            + " (cron '"
            + cron
            + "'): "
            + FusionStack.newProcesses(name, known));

    JsonNode process = FusionStack.awaitFinishedOptimization(name, known);
    assertSucceededAs(process, "MINOR");
    long started = process.path("startTime").asLong();
    assertTrue(
        started >= targetMillis - 1000 && started <= targetMillis + FIRE_LATENCY.toMillis(),
        "started at the scheduled minute " + target + ": " + FusionStack.describe(process));
    // Later ticks inside the fired window may only add SKIPPED records.
    long ran =
        FusionStack.newProcesses(name, known).stream()
            .filter(p -> !"SKIPPED".equals(FusionStack.status(p)))
            .count();
    assertEquals(1, ran, "exactly one process ran: " + FusionStack.newProcesses(name, known));
  }
}
