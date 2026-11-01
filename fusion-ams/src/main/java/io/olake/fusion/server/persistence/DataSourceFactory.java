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

package io.olake.fusion.server.persistence;

import io.olake.fusion.config.Configurations;
import io.olake.fusion.server.FusionManagementConf;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.pool2.impl.BaseObjectPoolConfig;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

/** Factory to help create data source */
public class DataSourceFactory {
  private static final Logger LOG = LoggerFactory.getLogger(DataSourceFactory.class);
  private static final String POSTGRES_INIT_SQL_SCRIPT = "postgres/ams-postgres-init.sql";

  public static DataSource createDataSource(Configurations config) {
    String dbType = config.getString(FusionManagementConf.DB_TYPE);
    if (!FusionManagementConf.DB_TYPE_POSTGRES.equals(dbType)) {
      throw new IllegalArgumentException(
          String.format(
              "Unsupported %s: %s, only %s is supported",
              FusionManagementConf.DB_TYPE.key(), dbType, FusionManagementConf.DB_TYPE_POSTGRES));
    }
    BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUrl(config.getString(FusionManagementConf.DB_CONNECTION_URL));
    dataSource.setDriverClassName(config.getString(FusionManagementConf.DB_DRIVER_CLASS_NAME));
    dataSource.setUsername(config.getString(FusionManagementConf.DB_USER_NAME));
    dataSource.setPassword(config.getString(FusionManagementConf.DB_PASSWORD));
    dataSource.setDefaultAutoCommit(false);
    dataSource.setMaxTotal(config.getInteger(FusionManagementConf.DB_CONNECT_MAX_TOTAL));
    dataSource.setMaxIdle(config.getInteger(FusionManagementConf.DB_CONNECT_MAX_IDLE));
    dataSource.setMinIdle(0);
    dataSource.setMaxWaitMillis(config.getLong(FusionManagementConf.DB_CONNECT_MAX_WAIT_MILLIS));
    dataSource.setLogAbandoned(true);
    dataSource.setRemoveAbandonedOnBorrow(true);
    dataSource.setRemoveAbandonedTimeout(60);
    dataSource.setTimeBetweenEvictionRunsMillis(Duration.ofMillis(10 * 60 * 1000L).toMillis());
    dataSource.setTestOnBorrow(BaseObjectPoolConfig.DEFAULT_TEST_ON_BORROW);
    dataSource.setTestWhileIdle(BaseObjectPoolConfig.DEFAULT_TEST_WHILE_IDLE);
    dataSource.setMinEvictableIdleTimeMillis(1000);
    dataSource.setNumTestsPerEvictionRun(BaseObjectPoolConfig.DEFAULT_NUM_TESTS_PER_EVICTION_RUN);
    dataSource.setTestOnReturn(BaseObjectPoolConfig.DEFAULT_TEST_ON_RETURN);
    dataSource.setSoftMinEvictableIdleTimeMillis(
        BaseObjectPoolConfig.DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME.toMillis());
    dataSource.setLifo(BaseObjectPoolConfig.DEFAULT_LIFO);
    createTablesIfNeed(dataSource, config);
    SchemaMigrator.migrate(dataSource, config);
    return dataSource;
  }

  /** create tables for database */
  private static void createTablesIfNeed(DataSource ds, Configurations config) {
    boolean initSchema = config.getBoolean(FusionManagementConf.DB_AUTO_CREATE_TABLES);
    if (!initSchema) {
      LOG.info("Skip auto create tables due to configuration");
      return;
    }
    String query =
        "SELECT 1 FROM information_schema.tables"
            + " WHERE table_schema = current_schema() AND table_name = 'catalog_metadata'";
    LOG.info("Start create tables");

    try (Connection connection = ds.getConnection();
        Statement statement = connection.createStatement()) {
      LOG.info("Start check table creation, using query: {}", query);
      try (ResultSet rs = statement.executeQuery(query)) {
        if (!rs.next()) {
          Path script = Paths.get(getInitSqlScriptPath());
          LOG.info("Table not exists, start run create tables script file:{}", script);
          ScriptRunner runner = new ScriptRunner(connection);
          runner.runScript(
              new InputStreamReader(Files.newInputStream(script), StandardCharsets.UTF_8));
          LOG.info("Tables are created successfully");
        } else {
          LOG.info("Tables are created, skip auto create tables.");
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("Create tables failed", e);
    }
  }

  private static URI getInitSqlScriptPath() throws URISyntaxException {
    URL scriptUrl = ClassLoader.getSystemResource(POSTGRES_INIT_SQL_SCRIPT);
    if (scriptUrl == null) {
      throw new IllegalStateException("Cannot find init sql script:" + POSTGRES_INIT_SQL_SCRIPT);
    }
    return scriptUrl.toURI();
  }
}
