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

package io.olake.fusion.server;

import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;

import java.util.Map;

/** Reads the server section of `config.yaml`. */
public class ServerConfigLoader {

  /** Root key of the server section. */
  public static final String ROOT_KEY = "fusion";

  /**
   * Legacy root key, used by every existing `config.yaml` (OLake-UI, OLake-Helm, `local-test/`).
   */
  public static final String LEGACY_ROOT_KEY = FusionManagementConf.SYSTEM_CONFIG;

  private ServerConfigLoader() {}

  /** The server section under the `fusion:` root key, or the legacy `ams:` root key. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> systemConfig(Map<String, Object> yaml) {
    Object section = yaml.containsKey(ROOT_KEY) ? yaml.get(ROOT_KEY) : yaml.get(LEGACY_ROOT_KEY);
    Preconditions.checkArgument(
        section instanceof Map,
        "config.yaml must have a `%s:` (or legacy `%s:`) section",
        ROOT_KEY,
        LEGACY_ROOT_KEY);
    return (Map<String, Object>) section;
  }
}
