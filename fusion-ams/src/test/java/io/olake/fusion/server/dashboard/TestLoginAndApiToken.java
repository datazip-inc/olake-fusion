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

package io.olake.fusion.server.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.exception.SignatureCheckException;
import io.olake.fusion.server.AMSManagerTestBase;
import io.olake.fusion.server.FusionManagementConf;
import io.olake.fusion.server.dashboard.controller.ApiTokenController;
import io.olake.fusion.server.dashboard.controller.LoginController;
import io.olake.fusion.server.dashboard.model.ApiTokens;
import io.olake.fusion.server.dashboard.response.OkResponse;
import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The login flow OLake-UI uses: password login, then an API token, then signed calls. */
public class TestLoginAndApiToken extends AMSManagerTestBase {

  private static Configurations adminConfig() {
    Configurations config = new Configurations();
    config.set(FusionManagementConf.ADMIN_USERNAME, "admin");
    config.set(FusionManagementConf.ADMIN_PASSWORD, "password");
    return config;
  }

  private static Context loginContext(String user, String password) {
    Context ctx = mock(Context.class);
    when(ctx.bodyAsClass(Map.class))
        .thenReturn(ImmutableMap.of("user", user, "password", password));
    return ctx;
  }

  /**
   * Signature as OLake-UI's `calculateSignature` builds it: md5(apiKey + sorted params + secret).
   */
  private static String olakeUiSignature(String apiKey, String secret, String key, String value) {
    return DigestUtils.md5Hex(apiKey + key + value + secret);
  }

  private static Context signedCall(String apiKey, String signature) {
    // Javalin hands over a mutable map; the server removes apiKey and signature from it.
    Map<String, List<String>> params = new HashMap<>();
    params.put("apiKey", Lists.newArrayList(apiKey));
    params.put("signature", Lists.newArrayList(signature));
    params.put("catalog", Lists.newArrayList("olake_iceberg"));
    Context ctx = mock(Context.class);
    when(ctx.url()).thenReturn("http://localhost:1630/api/ams/v1/catalogs");
    when(ctx.queryParam("apiKey")).thenReturn(apiKey);
    when(ctx.queryParam("signature")).thenReturn(signature);
    when(ctx.queryParamMap()).thenReturn(params);
    return ctx;
  }

  @Test
  public void passwordLoginThenSignedTokenCallWorks() {
    LoginController login = new LoginController(adminConfig());
    Context ok = loginContext("admin", "password");
    login.login(ok);
    verify(ok).sessionAttribute(eq("user"), any());
    Assert.assertThrows(
        RuntimeException.class, () -> login.login(loginContext("admin", "wrong-password")));

    ApiTokenController tokens = new ApiTokenController(new APITokenManager());
    Context create = mock(Context.class);
    tokens.createApiToken(create);
    ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
    verify(create).json(response.capture());
    ApiTokens token = (ApiTokens) ((OkResponse<?>) response.getValue()).getResult();

    String signature =
        olakeUiSignature(token.getApikey(), token.getSecret(), "catalog", "olake_iceberg");
    tokens.checkApiToken(signedCall(token.getApikey(), signature));
    Assert.assertThrows(
        SignatureCheckException.class,
        () -> tokens.checkApiToken(signedCall(token.getApikey(), "0" + signature.substring(1))));
  }
}
