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

import io.olake.fusion.config.ConfigHelpers;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableSet;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Server configuration overrides read from environment variables. */
public class EnvConfig {

  /** Prefix of server config overrides, for example {@code FUSION_DATABASE_URL}. */
  public static final String PREFIX = "FUSION";

  /**
   * Legacy prefix, still set by OLake-Helm and OLake-UI's compose, for example {@code
   * AMS_DATABASE_URL}.
   */
  public static final String LEGACY_PREFIX = "AMS";

  /** Variables the start scripts set for themselves; they are not server config. */
  private static final Set<String> SCRIPT_VARIABLES =
      ImmutableSet.of(
          "FUSION_HOME",
          "FUSION_CONF_DIR",
          "FUSION_PLUGIN_DIR",
          "FUSION_LOG_DIR",
          "FUSION_LOG_CONF_FILE",
          "FUSION_ADDITION_CLASSPATH",
          "FUSION_ENV_FILE");

  private EnvConfig() {}

  /** Config keys from `FUSION_*` and legacy `AMS_*` variables; `FUSION_*` wins. */
  public static Map<String, Object> fromEnv(Map<String, String> env) {
    Map<String, String> candidates =
        env.entrySet().stream()
            .filter(e -> !SCRIPT_VARIABLES.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    Map<String, Object> config =
        new HashMap<>(ConfigHelpers.convertConfigurationKeys(LEGACY_PREFIX, candidates));
    config.putAll(ConfigHelpers.convertConfigurationKeys(PREFIX, candidates));
    return config;
  }
}
