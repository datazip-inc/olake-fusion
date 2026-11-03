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

import io.olake.fusion.config.ConfigOptions;
import io.olake.fusion.config.Configurations;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestFusionManagementConfValidator {

  private static Configurations validPostgresConfig() {
    Configurations config = new Configurations();
    config.set(FusionManagementConf.SERVER_EXPOSE_HOST, "127.0.0.1");
    config.set(FusionManagementConf.DB_TYPE, FusionManagementConf.DB_TYPE_POSTGRES);
    config.set(FusionManagementConf.DB_USER_NAME, "fusion");
    config.set(FusionManagementConf.DB_PASSWORD, "fusion");
    return config;
  }

  @ParameterizedTest
  @ValueSource(strings = {"basic", "jwt"})
  public void rejectsNonTokenRestAuth(String authType) {
    Configurations config = validPostgresConfig();
    config.set(FusionManagementConf.HTTP_SERVER_REST_AUTH_TYPE, authType);

    IllegalArgumentException e =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> FusionManagementConfValidator.validateConfig(config));
    Assertions.assertTrue(e.getMessage().contains("http-server.rest-auth-type"), e.getMessage());
    Assertions.assertTrue(e.getMessage().contains("token"), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "login-auth-provider",
        "auth-basic-provider",
        "login-auth-ldap-url",
        "login-auth-ldap-user-pattern",
        "auth-jwt-provider"
      })
  public void rejectsRemovedAuthProviderKeys(String key) {
    Configurations config = validPostgresConfig();
    config.setString(
        ConfigOptions.key("http-server." + key).stringType().noDefaultValue(), "configured");

    IllegalArgumentException e =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> FusionManagementConfValidator.validateConfig(config));
    Assertions.assertTrue(e.getMessage().contains("http-server." + key), e.getMessage());
  }
}
