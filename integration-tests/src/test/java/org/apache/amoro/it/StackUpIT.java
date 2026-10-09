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
import org.apache.amoro.it.support.KubeOps;
import org.apache.amoro.it.support.StackEnv;
import org.apache.amoro.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

/**
 * Stack is up: login works, the Spark optimizer group exists, an optimizer is running and
 * heartbeating, and the JDBC Iceberg catalog is registered.
 */
class StackUpIT {

  @BeforeAll
  static void awaitOptimizer() {
    FusionStack.ensureOptimizerRunning();
  }

  @Test
  void loginWorks() {
    assertEquals(200, FusionStack.client().login(), "login envelope code");
    JsonNode user = FusionStack.client().currentUser();
    assertFalse(user.isNull() || user.isMissingNode(), "current user after login");
  }

  @Test
  void sparkOptimizerGroupExists() {
    assertTrue(
        FusionStack.client().resourceGroupNames().contains(StackEnv.OPTIMIZER_GROUP),
        "resource groups contain " + StackEnv.OPTIMIZER_GROUP);
  }

  @Test
  void optimizerIsRunningAndHeartbeating() {
    List<JsonNode> optimizers = FusionStack.liveOptimizers();
    assertFalse(optimizers.isEmpty(), "live optimizers in " + StackEnv.OPTIMIZER_GROUP);
    for (JsonNode optimizer : optimizers) {
      assertEquals("RUNNING", optimizer.path("jobStatus").asText());
      assertEquals("sparkContainer", optimizer.path("container").asText());
    }
    assertFalse(KubeOps.driverPods().isEmpty(), "Spark driver pod in the Kind cluster");

    long firstTouch = latestTouch();
    Await.until(
        "optimizer heartbeat to advance past " + firstTouch,
        Duration.ofMinutes(2),
        StackUpIT::latestTouch,
        touch -> touch > firstTouch);
  }

  @Test
  void jdbcIcebergCatalogIsRegistered() {
    assertTrue(
        FusionStack.client().catalogNames().contains(StackEnv.CATALOG),
        "catalogs contain " + StackEnv.CATALOG);
  }

  private static long latestTouch() {
    return FusionStack.client().optimizers(StackEnv.OPTIMIZER_GROUP).stream()
        .mapToLong(o -> o.path("touchTime").asLong())
        .max()
        .orElse(0L);
  }
}
