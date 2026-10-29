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

public class FusionManagementConfValidator {
  public static void validateConfig(Configurations configurations) {
    // SERVER_EXPOSE_HOST config
    if ("".equals(configurations.getString(FusionManagementConf.SERVER_EXPOSE_HOST))) {
      throw new IllegalArgumentException(
          "configuration " + FusionManagementConf.SERVER_EXPOSE_HOST.key() + " must be set");
    }
    InetAddress inetAddress =
        AmsUtil.lookForBindHost(configurations.getString(FusionManagementConf.SERVER_EXPOSE_HOST));
    configurations.setString(FusionManagementConf.SERVER_EXPOSE_HOST, inetAddress.getHostAddress());

    // mysql or postgres config
    if (FusionManagementConf.DB_TYPE_MYSQL.equalsIgnoreCase(
            configurations.getString(FusionManagementConf.DB_TYPE))
        || FusionManagementConf.DB_TYPE_POSTGRES.equalsIgnoreCase(
            configurations.getString(FusionManagementConf.DB_TYPE))) {
      if ("".equals(configurations.getString(FusionManagementConf.DB_PASSWORD))
          || "".equals(configurations.getString(FusionManagementConf.DB_USER_NAME))) {
        throw new IllegalArgumentException(
            "username and password must be configured if the database type is mysql or postgres");
      }
    }

    // HA config
    if (configurations.getBoolean(FusionManagementConf.HA_ENABLE)) {
      String type = configurations.getString(FusionManagementConf.HA_TYPE).toLowerCase();
      if (!FusionManagementConf.HA_TYPE_ZK.equals(type)
          && !FusionManagementConf.HA_TYPE_DATABASE.equals(type)) {
        throw new IllegalArgumentException(
            String.format("Illegal ha.type: %s, zk or database is available", type));
      }
      if (FusionManagementConf.HA_TYPE_ZK.equals(type)) {
        if ("".equals(configurations.getString(FusionManagementConf.HA_ZOOKEEPER_ADDRESS))) {
          throw new IllegalArgumentException(
              FusionManagementConf.HA_ZOOKEEPER_ADDRESS.key()
                  + " must be configured when ha.type=zk");
        }
      } else {
        String dbUrl = configurations.getString(FusionManagementConf.DB_CONNECTION_URL);
        if ("".equals(dbUrl)) {
          throw new IllegalArgumentException(
              "database.url must be configured when ha.type=database");
        }
      }
    }
    // terminal config
    String terminalBackend =
        configurations.getString(FusionManagementConf.TERMINAL_BACKEND).toLowerCase();
    if (!FusionManagementConf.TERMINAL_BACKEND_VALUES.contains(terminalBackend)) {
      throw new IllegalArgumentException(
          String.format(
              "Illegal terminal implement: %s, local, kyuubi, custom is available",
              terminalBackend));
    }

    validateThreadCount(configurations, FusionManagementConf.REFRESH_TABLES_THREAD_COUNT);
    validateThreadCount(configurations, FusionManagementConf.OPTIMIZING_COMMIT_THREAD_COUNT);

    if (configurations.getBoolean(FusionManagementConf.EXPIRE_SNAPSHOTS_ENABLED)) {
      validateThreadCount(configurations, FusionManagementConf.EXPIRE_SNAPSHOTS_THREAD_COUNT);
    }

    if (configurations.getBoolean(FusionManagementConf.CLEAN_ORPHAN_FILES_ENABLED)) {
      validateThreadCount(configurations, FusionManagementConf.CLEAN_ORPHAN_FILES_THREAD_COUNT);
    }
    if (configurations.getBoolean(FusionManagementConf.SYNC_HIVE_TABLES_ENABLED)) {
      validateThreadCount(configurations, FusionManagementConf.SYNC_HIVE_TABLES_THREAD_COUNT);
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
