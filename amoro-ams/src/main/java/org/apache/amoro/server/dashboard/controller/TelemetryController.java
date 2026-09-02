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

package org.apache.amoro.server.dashboard.controller;

import io.javalin.http.Context;
import org.apache.amoro.server.dashboard.response.OkResponse;
import org.apache.amoro.server.utils.Telemetry;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;

import java.util.Map;

/**
 * Receives the anonymous OLake install id from the OLake UI, which owns it. AMS keeps it in {@code
 * platform_property} so that every AMS restart and replica reports telemetry under the same id.
 */
public class TelemetryController {

  private static final String INSTALL_ID_FIELD = "install_id";

  @SuppressWarnings("unchecked")
  public void setInstallId(Context ctx) {
    Map<String, Object> body = ctx.bodyAsClass(Map.class);
    Object installId = body == null ? null : body.get(INSTALL_ID_FIELD);
    Preconditions.checkArgument(
        installId instanceof String, "install_id is required and must be a string");
    String effective = Telemetry.getInstance().applyInstallId((String) installId);
    ctx.json(OkResponse.of(Map.of(INSTALL_ID_FIELD, effective)));
  }
}
