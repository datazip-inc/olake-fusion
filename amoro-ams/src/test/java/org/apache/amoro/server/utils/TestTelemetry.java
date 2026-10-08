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

package org.apache.amoro.server.utils;

import org.apache.iceberg.exceptions.RESTException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

public class TestTelemetry {

  private static final String HOST = "secret-host.internal";

  /** The chain a Generic REST catalog with an unknown host produces in createCatalog. */
  private static Throwable unknownHostFailure() {
    RESTException rest =
        new RESTException(
            new UnknownHostException(HOST), "Error occurred while processing %s request", "GET");
    return new RuntimeException("Test Connection unsuccessful: " + HOST, rest);
  }

  private static Object categoryOf(Throwable error) {
    return Telemetry.failureProps(Telemetry.Command.CREATE_CATALOG, "rest", error).get("category");
  }

  @Test
  public void unknownHostIsReportedAsTheConnectorReportsIt() {
    Map<String, Object> props =
        Telemetry.failureProps(Telemetry.Command.CREATE_CATALOG, "rest", unknownHostFailure());

    Assertions.assertEquals(
        Map.of(
            "command", "create_catalog",
            "error_source", "rest",
            "category", "dns_resolution_failed",
            "code", "UnknownHostException"),
        props);
  }

  @Test
  public void unknownHostNeverLeaksTheHostName() {
    Map<String, Object> props =
        Telemetry.failureProps(Telemetry.Command.CREATE_CATALOG, "rest", unknownHostFailure());

    props.values().forEach(v -> Assertions.assertFalse(String.valueOf(v).contains(HOST)));
  }

  @Test
  public void noFailureOrUnmappedFailureSendsNothing() {
    Assertions.assertNull(Telemetry.failureProps(Telemetry.Command.CREATE_CATALOG, "rest", null));
    Assertions.assertNull(
        Telemetry.failureProps(
            Telemetry.Command.CREATE_CATALOG, "rest", new IllegalStateException(HOST)));
  }

  @Test
  public void reachabilityFailuresAreClassified() {
    Assertions.assertEquals(
        "network_unreachable", categoryOf(new RuntimeException(new ConnectException(HOST))));
    Assertions.assertEquals("timeout", categoryOf(new SocketTimeoutException(HOST)));
    Assertions.assertEquals("tls_failed", categoryOf(new SSLHandshakeException(HOST)));
  }
}
