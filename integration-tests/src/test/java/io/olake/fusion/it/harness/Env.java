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

package io.olake.fusion.it.harness;

/** Where the stack is reached from the test JVM, and the run's settings. */
public final class Env {
  public static final String FUSION_URL = get("TEST_FUSION_URL", "http://localhost:11630");
  public static final String SPARK_CONNECT_URL =
      get("TEST_SPARK_CONNECT_URL", "sc://localhost:15002");
  public static final String SOURCE_JDBC_HOST = get("TEST_SOURCE_HOST", "localhost:25432");
  public static final String OLAKE_IMAGE = get("OLAKE_IMAGE", "olakego/source-postgres:latest");
  public static final String DOCKER_NETWORK = "fusion-test-net";

  /**
   * Host directory the Fusion container's /mnt/amoro-logs is mounted from. The optimizer writes one
   * directory of logs per optimizing process under its {@code compaction} subdirectory.
   */
  public static final String LOG_DIR = get("TEST_LOG_DIR", "/tmp/fusion-test-logs");

  /** Suffix of every name a run creates, so parallel runs never collide. */
  public static final String RUN_ID =
      get("TEST_RUN_ID", String.valueOf(System.currentTimeMillis() / 1000 % 1_000_000));

  private Env() {}

  private static String get(String key, String defaultValue) {
    String value = System.getProperty(key);
    if (value == null || value.isEmpty()) {
      value = System.getenv(key);
    }
    return value == null || value.isEmpty() ? defaultValue : value;
  }
}
