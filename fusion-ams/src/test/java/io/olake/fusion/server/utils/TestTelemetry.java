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

package io.olake.fusion.server.utils;

import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.exception.AlreadyExistsException;
import io.olake.fusion.exception.ObjectNotExistsException;
import io.olake.fusion.exception.OptimizingCommitException;
import io.olake.fusion.server.catalog.CatalogManager;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.net.ssl.SSLHandshakeException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

public class TestTelemetry {

  private static final String HOST = "secret-host.internal";

  private static Map<String, Object> failureProps(Throwable error) {
    return Telemetry.failureProps(Command.CREATE_CATALOG, "rest", error);
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
  public void optimizerFailuresAreClassified() {
    // An optimizer registering for a group that does not exist.
    Assertions.assertEquals(
        "object_not_found",
        categoryOf(new io.olake.fusion.exception.ObjectNotExistsException("Optimizer group g")));
    // An optimizer registering with a heartbeat interval too long for AMS. It keeps the forbidden
    // error code, so that the optimizer still exits instead of retrying.
    io.olake.fusion.exception.OptimizerConfigException rejection =
        new io.olake.fusion.exception.OptimizerConfigException(HOST);
    Assertions.assertEquals("config_invalid", categoryOf(rejection));
    Assertions.assertEquals(
        io.olake.fusion.ErrorCodes.FORBIDDEN_ERROR_CODE,
        io.olake.fusion.exception.FusionRuntimeException.normalize(rejection).getErrorCode());
    Assertions.assertEquals(
        "internal_error",
        categoryOf(
            new io.olake.fusion.exception.IllegalTaskStateException(null, "ACKED", "PLANNED")));
  }

  @Test
  public void catalogFailuresAreClassified() {
    // A duplicate catalog name, thrown by createCatalog itself.
    Assertions.assertEquals("catalog_error", categoryOf(new AlreadyExistsException(HOST)));
    Assertions.assertEquals("config_invalid", categoryOf(new BadRequestException(HOST)));
    // Iceberg rejects bad catalog settings with IllegalArgumentException.
    Assertions.assertEquals("config_invalid", categoryOf(new IllegalArgumentException(HOST)));
  }

  @Test
  public void runtimeFailuresAreClassified() {
    Assertions.assertEquals("unsupported_feature", categoryOf(new UnsupportedOperationException()));
    Assertions.assertEquals("internal_error", categoryOf(new IllegalStateException(HOST)));
  }

  @Test
  public void subclassOfAMappedExceptionIsClassified() {
    // NumberFormatException is not listed; its superclass IllegalArgumentException is.
    Assertions.assertEquals("config_invalid", categoryOf(new NumberFormatException(HOST)));
  }

  @Test
  public void conflictingCompactionCommitIsReportedWithItsValidationException() {
    // How UnKeyedTableCommit reports a rewrite that conflicts with a concurrent write: Iceberg's
    // ValidationException, wrapped by the commit.
    Throwable failure =
        new OptimizingCommitException(
            "unexpected commit error ",
            new ValidationException(
                "Found conflicting files that can contain records matching true: %s", HOST));
    Map<String, Object> props = failureProps(failure);

    // ValidationException is not mapped, so the conflict is sent unclassified, with its root type.
    Assertions.assertEquals("unclassified", props.get("category"));
    Assertions.assertEquals(ValidationException.class.getName(), props.get("error_type"));
    props.values().forEach(v -> Assertions.assertFalse(String.valueOf(v).contains(HOST)));
  }

  @Test
  public void errorSourceIsTheCatalogTypeOfTheTable() {
    CatalogManager catalogManager = Mockito.mock(CatalogManager.class);
    CatalogMeta meta = new CatalogMeta();
    meta.setCatalogType("glue");
    Mockito.when(catalogManager.getCatalogMeta("lake")).thenReturn(meta);
    Mockito.when(catalogManager.getCatalogMeta("gone"))
        .thenThrow(new ObjectNotExistsException("Catalog gone"));

    Assertions.assertEquals("glue", Telemetry.errorSource(catalogManager, table("lake")));
    Assertions.assertNull(Telemetry.errorSource(catalogManager, table("gone")));
  }

  private static ServerTableIdentifier table(String catalog) {
    return ServerTableIdentifier.of(1L, catalog, "db", "t", TableFormat.ICEBERG);
  }
}
