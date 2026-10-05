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
import org.apache.amoro.server.persistence.PlatformPropertyStore;
import org.apache.amoro.server.utils.Telemetry;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;

import java.util.Map;

/**
 * Receives the anonymous OLake install id from the OLake UI, which owns it. AMS keeps it in {@code
 * platform_property} so that every AMS restart and replica reports telemetry under the same id.
 *
 * <p>Validation and storage live here rather than in {@link Telemetry}, whose methods never throw:
 * a bad id or a failed write must reach the caller as an error response.
 */
public class TelemetryController {

  private static final String INSTALL_ID_FIELD = "install_id";
  private static final int MAX_INSTALL_ID_LENGTH = 128;

  private final PlatformPropertyStore propertyStore = new PlatformPropertyStore();

  @SuppressWarnings("unchecked")
  public void setInstallId(Context ctx) {
    Map<String, Object> body = ctx.bodyAsClass(Map.class);
    Object installId = body == null ? null : body.get(INSTALL_ID_FIELD);
    Preconditions.checkArgument(
        installId instanceof String, "install_id is required and must be a string");
    String normalized = normalizeInstallId((String) installId);
    propertyStore.put(PlatformPropertyStore.TELEMETRY_INSTALL_ID, normalized);
    Telemetry.getInstance().useInstallId(normalized);
    ctx.json(OkResponse.of(Map.of(INSTALL_ID_FIELD, normalized)));
  }

  private static String normalizeInstallId(String id) {
    String normalized = id.trim();
    Preconditions.checkArgument(!normalized.isEmpty(), "Install id is empty");
    Preconditions.checkArgument(
        normalized.length() <= MAX_INSTALL_ID_LENGTH,
        "Install id is longer than %s characters",
        MAX_INSTALL_ID_LENGTH);
    Preconditions.checkArgument(
        normalized.matches("[A-Za-z0-9_-]+"),
        "Install id may only contain letters, digits, '-' and '_'");
    return normalized;
  }
}
