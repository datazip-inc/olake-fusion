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

import io.olake.fusion.config.ConfigOption;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.server.dashboard.utils.AmsUtil;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

public class FusionManagementConfValidator {

  /** Pluggable authentication keys that Fusion no longer reads; fail fast if one is configured. */
  private static final List<String> REMOVED_AUTH_KEYS =
      Arrays.asList(
          "http-server.login-auth-provider",
          "http-server.auth-basic-provider",
          "http-server.auth-jwt-provider",
          "http-server.login-auth-ldap-url",
          "http-server.login-auth-ldap-user-pattern");

  public static void validateConfig(Configurations configurations) {
    // SERVER_EXPOSE_HOST config
    if ("".equals(configurations.getString(FusionManagementConf.SERVER_EXPOSE_HOST))) {
      throw new IllegalArgumentException(
          "configuration " + FusionManagementConf.SERVER_EXPOSE_HOST.key() + " must be set");
    }
    InetAddress inetAddress =
        AmsUtil.lookForBindHost(configurations.getString(FusionManagementConf.SERVER_EXPOSE_HOST));
    configurations.setString(FusionManagementConf.SERVER_EXPOSE_HOST, inetAddress.getHostAddress());

    // PostgreSQL is the only supported database
    String dbType = configurations.getString(FusionManagementConf.DB_TYPE);
    if (!FusionManagementConf.DB_TYPE_POSTGRES.equals(dbType)) {
      throw new IllegalArgumentException(
          String.format(
              "Unsupported %s: %s, only %s is supported",
              FusionManagementConf.DB_TYPE.key(), dbType, FusionManagementConf.DB_TYPE_POSTGRES));
    }
    if ("".equals(configurations.getString(FusionManagementConf.DB_PASSWORD))
        || "".equals(configurations.getString(FusionManagementConf.DB_USER_NAME))) {
      throw new IllegalArgumentException("database username and password must be configured");
    }

    // REST API authentication: only session login and API-token signatures are supported
    String restAuthType = configurations.getString(FusionManagementConf.HTTP_SERVER_REST_AUTH_TYPE);
    if (!FusionManagementConf.HTTP_SERVER_REST_AUTH_TYPE_TOKEN.equalsIgnoreCase(restAuthType)) {
      throw new IllegalArgumentException(
          String.format(
              "Illegal %s: %s, only %s is supported",
              FusionManagementConf.HTTP_SERVER_REST_AUTH_TYPE.key(),
              restAuthType,
              FusionManagementConf.HTTP_SERVER_REST_AUTH_TYPE_TOKEN));
    }
    for (String key : REMOVED_AUTH_KEYS) {
      if (configurations.containsKey(key)) {
        throw new IllegalArgumentException(
            String.format("configuration %s is no longer supported, remove it", key));
      }
    }

    // terminal config: the local Spark backend is the only one
    String terminalBackend = configurations.getString(FusionManagementConf.TERMINAL_BACKEND);
    if (!FusionManagementConf.TERMINAL_BACKEND_LOCAL.equalsIgnoreCase(terminalBackend)) {
      throw new IllegalArgumentException(
          String.format(
              "Illegal %s: %s, only %s is supported",
              FusionManagementConf.TERMINAL_BACKEND.key(),
              terminalBackend,
              FusionManagementConf.TERMINAL_BACKEND_LOCAL));
    }

    validateThreadCount(configurations, FusionManagementConf.REFRESH_TABLES_THREAD_COUNT);
    validateThreadCount(configurations, FusionManagementConf.OPTIMIZING_COMMIT_THREAD_COUNT);

    if (configurations.getBoolean(FusionManagementConf.EXPIRE_SNAPSHOTS_ENABLED)) {
      validateThreadCount(configurations, FusionManagementConf.EXPIRE_SNAPSHOTS_THREAD_COUNT);
    }

    if (configurations.getBoolean(FusionManagementConf.CLEAN_ORPHAN_FILES_ENABLED)) {
      validateThreadCount(configurations, FusionManagementConf.CLEAN_ORPHAN_FILES_THREAD_COUNT);
    }
  }

  private static void validateThreadCount(
      Configurations configurations, ConfigOption<Integer> config) {
    int threadCount = configurations.getInteger(config);
    if (threadCount <= 0) {
      throw new IllegalArgumentException(
          String.format(
              "%s(%s) must > 0, actual value = %d",
              config.key(), config.description(), threadCount));
    }
  }
}
