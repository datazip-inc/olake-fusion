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

import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class TestEnvironments {

  @Test
  public void legacyAmoroEnvNamesStillWork() {
    Map<String, String> env =
        ImmutableMap.of("AMORO_HOME", "/opt/legacy", "AMORO_CONF_DIR", "/opt/legacy/conf-dir");

    Assertions.assertEquals("/opt/legacy", Environments.getHomePath(env));
    Assertions.assertEquals("/opt/legacy/conf-dir", Environments.getConfigPath(env));
  }

  @Test
  public void fusionEnvNamesWinOverLegacy() {
    Map<String, String> env =
        ImmutableMap.of(
            "AMORO_HOME", "/opt/legacy",
            "AMORO_CONF_DIR", "/opt/legacy/conf",
            "FUSION_HOME", "/opt/fusion",
            "FUSION_CONF_DIR", "/opt/fusion/conf");

    Assertions.assertEquals("/opt/fusion", Environments.getHomePath(env));
    Assertions.assertEquals("/opt/fusion/conf", Environments.getConfigPath(env));
  }
}
