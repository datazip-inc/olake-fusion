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

package org.apache.amoro.it.support;

import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Stack-level waits and assertions shared by all integration tests. */
public final class FusionStack {

  private static final Logger LOG = LoggerFactory.getLogger(FusionStack.class);

  /** Process statuses after which a process never changes again. */
  public static final Set<String> TERMINAL_STATUSES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList("SUCCESS", "FAILED", "CLOSED", "CANCELED", "KILLED", "SKIPPED")));

  /** Cron that fires every minute, used to trigger exactly one optimizing type per test. */
  public static final String EVERY_MINUTE = "* * * * *";

  public static final Duration OPTIMIZER_STARTUP = Duration.ofMinutes(10);
  public static final Duration TABLE_DISCOVERY = Duration.ofMinutes(3);
  public static final Duration OPTIMIZATION = Duration.ofMinutes(8);

  /** An optimizer counts as alive when AMS saw its heartbeat within this window. */
  private static final Duration HEARTBEAT_FRESHNESS = Duration.ofSeconds(60);

  private FusionStack() {}

  public static FusionClient client() {
    return FusionClient.shared();
  }

  // ---------------------------------------------------------------------------------------------
  // Optimizer
  // ---------------------------------------------------------------------------------------------

  public static List<JsonNode> liveOptimizers() {
    long now = System.currentTimeMillis();
    return client().optimizers(StackEnv.OPTIMIZER_GROUP).stream()
        .filter(o -> now - o.path("touchTime").asLong() < HEARTBEAT_FRESHNESS.toMillis())
        .collect(Collectors.toList());
  }

  /**
   * Waits until the Spark optimizer group has a live optimizer. If the group has no optimizer at
   * all, scales one out first.
   */
  public static synchronized void ensureOptimizerRunning() {
    if (client().optimizers(StackEnv.OPTIMIZER_GROUP).isEmpty() && KubeOps.driverPods().isEmpty()) {
      LOG.info("No optimizer in group {}, scaling out", StackEnv.OPTIMIZER_GROUP);
      client().scaleOutOptimizer(StackEnv.OPTIMIZER_GROUP, StackEnv.OPTIMIZER_PARALLELISM);
    }
    Await.until(
        "a live optimizer in group " + StackEnv.OPTIMIZER_GROUP,
        OPTIMIZER_STARTUP,
        FusionStack::liveOptimizers,
        optimizers -> !optimizers.isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // Tables
  // ---------------------------------------------------------------------------------------------

  public static void awaitTableListed(String db, String table) {
    Await.until(
        "Fusion lists " + StackEnv.CATALOG + "." + db + "." + table,
        TABLE_DISCOVERY,
        () -> client().tables(StackEnv.CATALOG, db),
        tables -> tables.contains(table));
  }

  /** Sets the trigger cron of one optimizing type (MINOR, MAJOR or FULL) to every minute. */
  public static void enableCron(Table table, String optimizingType) {
    IcebergFixture.setProperties(
        table,
        Map.of("self-optimizing." + optimizingType.toLowerCase() + ".trigger.cron", EVERY_MINUTE));
  }

  // ---------------------------------------------------------------------------------------------
  // Optimizing processes
  // ---------------------------------------------------------------------------------------------

  public static Set<String> processIds(String table) {
    return client().processes(StackEnv.CATALOG, StackEnv.TEST_DB, table).stream()
        .map(p -> p.path("processId").asText())
        .collect(Collectors.toSet());
  }

  /** Processes created after {@code knownIds} was taken, oldest first. */
  public static List<JsonNode> newProcesses(String table, Set<String> knownIds) {
    List<JsonNode> fresh = new ArrayList<>();
    for (JsonNode process : client().processes(StackEnv.CATALOG, StackEnv.TEST_DB, table)) {
      if (!knownIds.contains(process.path("processId").asText())) {
        fresh.add(process);
      }
    }
    fresh.sort((a, b) -> Long.compare(a.path("startTime").asLong(), b.path("startTime").asLong()));
    return fresh;
  }

  /** Waits for the first new process that matches {@code match}. */
  public static JsonNode awaitProcess(
      String table,
      Set<String> knownIds,
      String description,
      Duration timeout,
      Predicate<JsonNode> match) {
    Optional<JsonNode> found =
        Await.until(
            description + " on " + table,
            timeout,
            () -> newProcesses(table, knownIds).stream().filter(match).findFirst(),
            Optional::isPresent);
    return found.get();
  }

  /**
   * Waits for the first new non-SKIPPED process to finish and returns it. The caller asserts type
   * and status, so a wrong type or a FAILED process surfaces with its details.
   */
  public static JsonNode awaitFinishedOptimization(String table, Set<String> knownIds) {
    return awaitProcess(
        table,
        knownIds,
        "a finished optimizing process",
        OPTIMIZATION,
        p -> !"SKIPPED".equals(status(p)) && TERMINAL_STATUSES.contains(status(p)));
  }

  public static String status(JsonNode process) {
    return process.path("status").asText();
  }

  public static String type(JsonNode process) {
    String type = process.path("optimizingType").asText();
    if (type.isEmpty()) {
      type = process.path("summary").path("optimizingType").asText();
    }
    return type.toUpperCase();
  }

  public static String describe(JsonNode process) {
    return String.format(
        "process %s type=%s status=%s failReason=%s",
        process.path("processId").asText(),
        type(process),
        status(process),
        process.path("failReason").asText());
  }
}
