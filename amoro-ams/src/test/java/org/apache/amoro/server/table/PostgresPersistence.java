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

package org.apache.amoro.server.table;

import org.apache.amoro.config.Configurations;
import org.apache.amoro.server.AmoroManagementConf;
import org.apache.amoro.server.persistence.DataSourceFactory;
import org.apache.amoro.server.persistence.SqlSessionFactoryProvider;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.amoro.utils.PostgresTestDatabase;
import org.apache.ibatis.session.SqlSession;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class PostgresPersistence extends ExternalResource {
  private static final Logger LOG = LoggerFactory.getLogger(PostgresPersistence.class);

  private static final int TRUNCATE_RETRY_NUM = 3;

  static {
    try {
      String url = PostgresTestDatabase.create("ams_test");
      Configurations configurations = new Configurations();
      configurations.set(AmoroManagementConf.DB_CONNECTION_URL, url);
      configurations.set(AmoroManagementConf.DB_TYPE, AmoroManagementConf.DB_TYPE_POSTGRES);
      configurations.set(AmoroManagementConf.DB_DRIVER_CLASS_NAME, PostgresTestDatabase.DRIVER);
      configurations.set(AmoroManagementConf.DB_USER_NAME, PostgresTestDatabase.USER);
      configurations.set(AmoroManagementConf.DB_PASSWORD, PostgresTestDatabase.PASSWORD);
      DataSource ds = DataSourceFactory.createDataSource(configurations);
      SqlSessionFactoryProvider.getInstance().init(ds);
      LOG.info("Initialized postgres persistence with url: {}", url);
      truncateAllTables();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void after() {
    truncateAllTables();
  }

  private static void truncateAllTables() {
    try (SqlSession sqlSession = SqlSessionFactoryProvider.getInstance().get().openSession(true)) {
      try (Connection connection = sqlSession.getConnection()) {
        try (Statement statement = connection.createStatement()) {
          String query = "SELECT tablename FROM pg_tables WHERE schemaname = current_schema()";
          List<String> tableList = Lists.newArrayList();
          try (ResultSet rs = statement.executeQuery(query)) {
            while (rs.next()) {
              tableList.add(rs.getString(1));
            }
          }
          for (String table : tableList) {
            int retry = TRUNCATE_RETRY_NUM;
            while (true) {
              try {
                statement.execute("TRUNCATE TABLE " + table + " CASCADE");
                break;
              } catch (SQLException e) {
                if (retry > 0) {
                  LOG.warn("Failed to truncate {}, retry later.", table, e);
                  retry--;
                } else {
                  throw e;
                }
              }
            }
          }
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Clear table failed", e);
    }
  }
}
