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

/**
 * Endpoints and credentials of the stack started by {@code integration-tests/scripts/stack-up.sh}.
 * Every value can be overridden by a system property or an environment variable of the same name.
 */
public final class StackEnv {

  public static final String FUSION_URL = get("IT_FUSION_URL", "http://localhost:11630");
  public static final String FUSION_USER = get("IT_FUSION_USER", "admin");
  public static final String FUSION_PASSWORD = get("IT_FUSION_PASSWORD", "password");

  public static final String CATALOG = get("IT_CATALOG", "olake_iceberg");
  public static final String OPTIMIZER_GROUP = get("IT_OPTIMIZER_GROUP", "spark-container");
  public static final int OPTIMIZER_PARALLELISM =
      Integer.parseInt(get("IT_OPTIMIZER_PARALLELISM", "2"));

  public static final String CATALOG_JDBC_URL =
      get("IT_CATALOG_JDBC_URL", "jdbc:postgresql://localhost:15432/iceberg");
  public static final String CATALOG_JDBC_USER = "iceberg";
  public static final String CATALOG_JDBC_PASSWORD = "password";
  public static final String WAREHOUSE = "s3://warehouse/olake_iceberg/";

  public static final String S3_ENDPOINT = get("IT_S3_ENDPOINT", "http://localhost:19000");
  public static final String S3_ACCESS_KEY = "admin";
  public static final String S3_SECRET_KEY = "password";
  public static final String S3_REGION = "us-east-1";

  public static final String KIND_CONTROL_PLANE =
      get("IT_KIND_CONTROL_PLANE", "fusion-it-cluster-control-plane");
  public static final String SPARK_NAMESPACE = "spark";

  /** Iceberg namespace that holds every table the tests create through the Iceberg API. */
  public static final String TEST_DB = get("IT_TEST_DB", "it");

  private StackEnv() {}

  private static String get(String key, String defaultValue) {
    String value = System.getProperty(key);
    if (value == null || value.isEmpty()) {
      value = System.getenv(key);
    }
    return value == null || value.isEmpty() ? defaultValue : value;
  }
}
