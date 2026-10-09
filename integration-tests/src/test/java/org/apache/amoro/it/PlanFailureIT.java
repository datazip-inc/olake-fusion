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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.amoro.it.support.Await;
import org.apache.amoro.it.support.FusionStack;
import org.apache.amoro.it.support.IcebergFixture;
import org.apache.amoro.it.support.IcebergFixture.Layout;
import org.apache.amoro.it.support.StackEnv;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Plan failure: an error thrown while a scheduled table is being planned is caught by planFailed,
 * which records the process as FAILED and puts the table back to idle without affecting AMS.
 *
 * <p>The error comes from deleting one data manifest of the current snapshot: the table still loads
 * (its metadata and manifest list are intact), but the planner's file scan reads the deleted
 * manifest and throws.
 */
class PlanFailureIT extends OptimizationTestBase {

  private static final String MAJOR_CRON = "self-optimizing.major.trigger.cron";

  @Test
  void errorDuringPlanningMarksProcessFailed() {
    String name = IcebergFixture.uniqueName("it_plan_fail");
    Table table = iceberg.createTable(name, Layout.UNPARTITIONED, optimizationProperties());
    for (int i = 0; i < 4; i++) {
      appendFiles(table, Layout.UNPARTITIONED, UNDERSIZED_SEGMENT_ROWS);
    }

    List<ManifestFile> manifests = table.currentSnapshot().dataManifests(table.io());
    assertFalse(manifests.isEmpty(), "setup wrote data manifests");
    String manifest = manifests.get(0).path();
    table.io().deleteFile(manifest);
    String manifestName = manifest.substring(manifest.lastIndexOf('/') + 1);

    FusionStack.awaitTableListed(StackEnv.TEST_DB, name);
    Set<String> known = FusionStack.processIds(name);
    FusionStack.enableCron(table, "MAJOR");
    JsonNode process = FusionStack.awaitFinishedOptimization(name, known);

    assertEquals("FAILED", FusionStack.status(process), FusionStack.describe(process));
    assertEquals("MAJOR", FusionStack.type(process), FusionStack.describe(process));
    String failReason = process.path("failReason").asText();
    assertFalse(failReason.isEmpty(), "failReason recorded: " + FusionStack.describe(process));
    assertTrue(
        failReason.contains(manifestName)
            || failReason.matches("(?is).*(not ?found|no ?such|does not exist).*"),
        "failReason names the missing manifest " + manifestName + ": " + failReason);

    // Stop the cron so the next tick does not plan (and fail) again, then the table must settle.
    table.updateProperties().remove(MAJOR_CRON).commit();
    Await.until(
        "table " + name + " back to idle after the failed plan",
        Duration.ofMinutes(1),
        () -> FusionStack.client().optimizingStatus(StackEnv.OPTIMIZER_GROUP, name),
        status -> "idle".equalsIgnoreCase(status));

    assertEquals(200, FusionStack.client().login(), "AMS still serves logins");
    assertTrue(
        FusionStack.client().catalogNames().contains(StackEnv.CATALOG), "catalog still registered");
    assertFalse(FusionStack.liveOptimizers().isEmpty(), "optimizer still heartbeating");
  }
}
