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

import org.apache.amoro.exception.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.RESTException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

public class TestTelemetry {

  private static final String HOST = "secret-host.internal";

  private static Map<String, Object> failureProps(Throwable error) {
    return Telemetry.failureProps(Telemetry.Command.CREATE_CATALOG, "rest", error);
  }

  /** The chain a Generic REST catalog with an unknown host produces in createCatalog. */
  private static Throwable unknownHostFailure() {
    RESTException rest =
        new RESTException(
            new UnknownHostException(HOST), "Error occurred while processing %s request", "GET");
    return new RuntimeException("Test Connection unsuccessful: " + HOST, rest);
  }

  @Test
  public void unknownHostIsReportedAsTheConnectorReportsIt() {
    Map<String, Object> props = failureProps(unknownHostFailure());

    Assertions.assertEquals(
        Map.of(
            "command", "create_catalog",
            "error_source", "rest",
            "category", "dns_resolution_failed",
            "fusion_version", "NA",
            "optimizer_version", "NA"),
        props);
  }

  @Test
  public void unknownHostNeverLeaksTheHostName() {
    Map<String, Object> props = failureProps(unknownHostFailure());

    props.values().forEach(v -> Assertions.assertFalse(String.valueOf(v).contains(HOST)));
  }

  @Test
  public void unknownHostIsFoundMidChain() {
    Throwable failure =
        new RuntimeException(new UnknownHostException(HOST).initCause(new IOException(HOST)));
    Map<String, Object> props = failureProps(failure);

    Assertions.assertEquals("dns_resolution_failed", props.get("category"));
  }

  @Test
  public void noFailureSendsNothing() {
    Assertions.assertNull(failureProps(null));
  }

  @Test
  public void unmappedFailureIsReportedAsUnclassified() {
    Map<String, Object> props = failureProps(new Exception(HOST));

    Assertions.assertEquals(
        Map.of(
            "command", "create_catalog",
            "error_source", "rest",
            "category", "unclassified",
            "fusion_version", "NA",
            "optimizer_version", "NA",
            "error_type", "java.lang.Exception"),
        props);
  }

  private static Object categoryOf(Throwable error) {
    return failureProps(error).get("category");
  }

  @Test
  public void reachabilityFailuresAreClassified() {
    Assertions.assertEquals(
        "network_unreachable", categoryOf(new RuntimeException(new ConnectException(HOST))));
    Assertions.assertEquals("timeout", categoryOf(new SocketTimeoutException(HOST)));
    Assertions.assertEquals("tls_failed", categoryOf(new SSLHandshakeException(HOST)));
  }

  @Test
  public void authFailuresAreClassified() {
    Assertions.assertEquals("auth_failed", categoryOf(new NotAuthorizedException(HOST)));
    Assertions.assertEquals("permission_denied", categoryOf(new ForbiddenException(HOST)));
  }

  @Test
  public void notFoundFailuresAreClassified() {
    Assertions.assertEquals("object_not_found", categoryOf(new NoSuchNamespaceException(HOST)));
  }

  @Test
  public void catalogFailuresAreClassified() {
    // A duplicate catalog name, thrown by createCatalog itself.
    Assertions.assertEquals("catalog_error", categoryOf(new AlreadyExistsException(HOST)));
    Assertions.assertEquals("config_invalid", categoryOf(new BadRequestException(HOST)));
    Assertions.assertEquals("concurrency_conflict", categoryOf(new CommitFailedException(HOST)));
  }

  @Test
  public void runtimeFailuresAreClassified() {
    Assertions.assertEquals("unsupported_feature", categoryOf(new UnsupportedOperationException()));
    Assertions.assertEquals("internal_error", categoryOf(new IllegalStateException(HOST)));
  }

  @Test
  public void subclassOfAMappedExceptionIsClassified() {
    // NumberFormatException is not listed; its superclass IllegalArgumentException is.
    Assertions.assertEquals("internal_error", categoryOf(new NumberFormatException(HOST)));
  }

  @Test
  public void optimizerTaskTraceIsClassifiedByItsMappedCause() {
    String trace =
        "java.lang.RuntimeException: write failed for s3://secret-bucket/t\n"
            + "\tat org.apache.amoro.optimizer.Executor.run(Executor.java:10)\n"
            + "Caused by: java.net.SocketTimeoutException: connect timed out "
            + HOST
            + "\n"
            + "\t... 3 more\n"
            + "Caused by: java.io.IOException: "
            + HOST
            + "\n";

    Assertions.assertEquals(
        Map.of(
            "command", "execute_optimizing",
            "error_source", "iceberg",
            "category", "timeout",
            "fusion_version", "NA",
            "optimizer_version", "NA"),
        Telemetry.traceFailureProps(Telemetry.Command.EXECUTE_OPTIMIZING, "iceberg", trace));
  }

  @Test
  public void unmappedTraceIsReportedWithItsRootType() {
    String trace =
        "java.lang.RuntimeException: write failed\n"
            + "Caused by: org.example.Outer$CustomException: "
            + HOST
            + "\n";

    Assertions.assertEquals(
        Map.of(
            "command", "execute_optimizing",
            "error_source", "iceberg",
            "category", "unclassified",
            "fusion_version", "NA",
            "optimizer_version", "NA",
            "error_type", "org.example.Outer$CustomException"),
        Telemetry.traceFailureProps(Telemetry.Command.EXECUTE_OPTIMIZING, "iceberg", trace));
  }

  @Test
  public void traceWithoutExceptionSendsNothing() {
    Assertions.assertNull(
        Telemetry.traceFailureProps(Telemetry.Command.EXECUTE_OPTIMIZING, "iceberg", "no trace"));
  }
}
