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

import io.olake.fusion.config.Configurations;
import org.junit.Assert;
import org.junit.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

public class TestAmoroManagementConfValidator {

  @Test
  public void testBlankServerExposeHost() {
    Configurations configurations = new Configurations();
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));
  }

  @Test
  public void testValidateServerExposeHost() {
    Configurations configurations = new Configurations();
    configurations.setString(FusionManagementConf.SERVER_EXPOSE_HOST, "0.0.0.0");
    Assert.assertThrows(
        RuntimeException.class, () -> FusionManagementConfValidator.validateConfig(configurations));
  }

  public static Stream<Arguments> testValidateDBConfig() {
    return Stream.of(Arguments.of("mysql"), Arguments.of("postgres"));
  }

  @ParameterizedTest
  @MethodSource
  public void testValidateDBConfig(String dbType) {
    Configurations configurations = new Configurations();
    configurations.setString(FusionManagementConf.SERVER_EXPOSE_HOST, "127.0.0.1");
    configurations.setString(FusionManagementConf.DB_TYPE, dbType);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));

    configurations.setString(FusionManagementConf.DB_PASSWORD, "123456");
    configurations.setString(FusionManagementConf.DB_USER_NAME, "");
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));

    configurations.removeConfig(FusionManagementConf.DB_PASSWORD);
    configurations.setString(FusionManagementConf.DB_USER_NAME, "root");
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));

    configurations.setString(FusionManagementConf.DB_PASSWORD, "123456");
    FusionManagementConfValidator.validateConfig(configurations);
  }

  @Test
  public void testValidateHAConfig() {
    Configurations configurations = new Configurations();
    configurations.setString(FusionManagementConf.SERVER_EXPOSE_HOST, "127.0.0.1");
    configurations.setBoolean(FusionManagementConf.HA_ENABLE, true);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));

    configurations.setString(FusionManagementConf.HA_ZOOKEEPER_ADDRESS, "127.0.0.1:2181");
    FusionManagementConfValidator.validateConfig(configurations);
  }

  @Test
  public void testValidateTerminalBackend() {
    Configurations configurations = new Configurations();
    configurations.setString(FusionManagementConf.SERVER_EXPOSE_HOST, "127.0.0.1");

    configurations.setString(FusionManagementConf.TERMINAL_BACKEND, "invalid_terminal_backend");
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));

    configurations.setString(FusionManagementConf.TERMINAL_BACKEND, "local");
    FusionManagementConfValidator.validateConfig(configurations);

    configurations.setString(FusionManagementConf.TERMINAL_BACKEND, "kyuubi");
    FusionManagementConfValidator.validateConfig(configurations);

    configurations.setString(FusionManagementConf.TERMINAL_BACKEND, "custom");
    FusionManagementConfValidator.validateConfig(configurations);
  }

  @Test
  public void testValidateThreadCount() {
    Configurations configurations = new Configurations();
    configurations.setString(FusionManagementConf.SERVER_EXPOSE_HOST, "127.0.0.1");

    configurations.setInteger(FusionManagementConf.REFRESH_TABLES_THREAD_COUNT, -1);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));
    configurations.setInteger(FusionManagementConf.REFRESH_TABLES_THREAD_COUNT, 10);
    FusionManagementConfValidator.validateConfig(configurations);

    configurations.setInteger(FusionManagementConf.OPTIMIZING_COMMIT_THREAD_COUNT, -1);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));
    configurations.setInteger(FusionManagementConf.OPTIMIZING_COMMIT_THREAD_COUNT, 10);
    FusionManagementConfValidator.validateConfig(configurations);

    configurations.setBoolean(FusionManagementConf.EXPIRE_SNAPSHOTS_ENABLED, true);
    configurations.setInteger(FusionManagementConf.EXPIRE_SNAPSHOTS_THREAD_COUNT, -1);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));
    configurations.setInteger(FusionManagementConf.EXPIRE_SNAPSHOTS_THREAD_COUNT, 10);
    FusionManagementConfValidator.validateConfig(configurations);

    configurations.setBoolean(FusionManagementConf.CLEAN_ORPHAN_FILES_ENABLED, true);
    configurations.setInteger(FusionManagementConf.CLEAN_ORPHAN_FILES_THREAD_COUNT, -1);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));
    configurations.setInteger(FusionManagementConf.CLEAN_ORPHAN_FILES_THREAD_COUNT, 10);
    FusionManagementConfValidator.validateConfig(configurations);

    configurations.setBoolean(FusionManagementConf.SYNC_HIVE_TABLES_ENABLED, true);
    configurations.setInteger(FusionManagementConf.SYNC_HIVE_TABLES_THREAD_COUNT, -1);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> FusionManagementConfValidator.validateConfig(configurations));
    configurations.setInteger(FusionManagementConf.SYNC_HIVE_TABLES_THREAD_COUNT, 10);
    FusionManagementConfValidator.validateConfig(configurations);
  }
}
