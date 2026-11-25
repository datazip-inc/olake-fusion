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

package io.olake.fusion.server.utils;

import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class TestEnvConfig {

  @Test
  public void legacyAmsEnvPrefixStillOverridesConfig() {
    Map<String, Object> config =
        EnvConfig.fromEnv(ImmutableMap.of("AMS_DATABASE_URL", "jdbc:postgresql://h:5432/db"));

    Assertions.assertEquals("jdbc:postgresql://h:5432/db", config.get("database.url"));
  }

  @Test
  public void fusionEnvPrefixWinsOverAms() {
    Map<String, Object> config =
        EnvConfig.fromEnv(
            ImmutableMap.of(
                "AMS_DATABASE_URL", "jdbc:postgresql://legacy:5432/db",
                "FUSION_DATABASE_URL", "jdbc:postgresql://fusion:5432/db"));

    Assertions.assertEquals("jdbc:postgresql://fusion:5432/db", config.get("database.url"));
  }
}
