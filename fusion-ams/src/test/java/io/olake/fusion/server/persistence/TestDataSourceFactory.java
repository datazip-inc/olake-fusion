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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestDataSourceFactory {

  @ParameterizedTest
  @CsvSource({
    "derby, org.apache.derby.jdbc.EmbeddedDriver, jdbc:derby:memory:fusion_t21;create=true",
    "mysql, com.mysql.cj.jdbc.Driver, jdbc:mysql://127.0.0.1:1/fusion"
  })
  public void rejectsNonPostgresDatabaseType(String type, String driver, String url) {
    Configurations config = new Configurations();
    config.set(FusionManagementConf.DB_TYPE, type);
    config.set(FusionManagementConf.DB_DRIVER_CLASS_NAME, driver);
    config.set(FusionManagementConf.DB_CONNECTION_URL, url);

    IllegalArgumentException e =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> DataSourceFactory.createDataSource(config));
    Assertions.assertTrue(e.getMessage().contains(type), e.getMessage());
    Assertions.assertTrue(e.getMessage().contains("postgres"), e.getMessage());
  }
}
