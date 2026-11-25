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

package io.olake.fusion.server.authentication;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.olake.fusion.config.Configurations;
import io.olake.fusion.exception.SignatureCheckException;
import io.olake.fusion.server.FusionManagementConf;
import org.junit.jupiter.api.Test;

public class TestDefaultPasswdAuthenticationProvider {
  @Test
  public void testPasswordAuthenticationProvider() {
    Configurations conf = new Configurations();
    conf.set(FusionManagementConf.ADMIN_USERNAME, "admin");
    conf.set(FusionManagementConf.ADMIN_PASSWORD, "password");

    PasswdAuthenticationProvider passwdAuthenticationProvider =
        new DefaultPasswdAuthenticationProvider(conf);

    assert passwdAuthenticationProvider
        .authenticate(new DefaultPasswordCredential("admin", "password"))
        .getName()
        .equals("admin");

    assertThrows(
        SignatureCheckException.class,
        () -> {
          passwdAuthenticationProvider.authenticate(
              new DefaultPasswordCredential("admin", "invalidPassword"));
        });
    assertThrows(
        SignatureCheckException.class,
        () -> {
          passwdAuthenticationProvider.authenticate(
              new DefaultPasswordCredential("nonAdmin", "password"));
        });
  }
}
