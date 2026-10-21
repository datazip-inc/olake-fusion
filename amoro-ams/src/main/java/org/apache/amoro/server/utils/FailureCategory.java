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

import java.util.HashMap;
import java.util.Map;

/**
 * Why an operation failed, as sent in a failure event's {@code category}. Each category lists the
 * exception simple class names it covers, as olake destination/iceberg/errors.go. Names, not
 * classes: most come from catalog clients that are not on the AMS classpath.
 */
enum FailureCategory {
  DNS_RESOLUTION_FAILED("UnknownHostException"),
  NETWORK_UNREACHABLE(
      "ConnectException",
      "NoRouteToHostException",
      "HttpHostConnectException",
      "NoHttpResponseException",
      "ConnectionClosedException",
      "SocketException",
      "TTransportException",
      "MetaException",
      "RuntimeMetaException",
      "ServiceUnavailableException",
      "InternalServiceException",
      "SQLTransientConnectionException",
      "SQLNonTransientConnectionException"),
  TIMEOUT(
      "SocketTimeoutException",
      "TimeoutException",
      "ConnectTimeoutException",
      "OperationTimeoutException",
      "SQLTimeoutException"),
  TLS_FAILED("SSLHandshakeException", "CertificateException", "SSLPeerUnverifiedException"),
  AUTH_FAILED(
      "NotAuthorizedException",
      "SaslException",
      "LoginException",
      "GSSException",
      "GoogleAuthException",
      "TokenResponseException",
      "SQLInvalidAuthorizationSpecException"),
  PERMISSION_DENIED(
      "ForbiddenException",
      "AccessDeniedException",
      "AccessControlException",
      "GlueEncryptionException"),
  OBJECT_NOT_FOUND(
      "NoSuchTableException",
      "NoSuchNamespaceException",
      "NoSuchViewException",
      "EntityNotFoundException",
      "NoSuchKeyException",
      "NoSuchWarehouseException",
      "NoSuchObjectException",
      "UnknownDBException",
      "NotFoundException",
      "NoSuchIcebergTableException",
      "NoSuchIcebergViewException",
      "ObjectNotExistsException"),
  /**
   * Iceberg throws IllegalArgumentException for bad catalog settings (a missing warehouse, a bad
   * URI, an unknown catalog-impl), which are user config mistakes.
   */
  CONFIG_INVALID(
      "BadRequestException",
      "UnprocessableEntityException",
      "InvalidInputException",
      "IllegalArgumentException",
      // An optimizer AMS turns away for its config, e.g. a heartbeat interval too long.
      "OptimizerConfigException"),
  CATALOG_ERROR(
      "AlreadyExistsException",
      "CommitStateUnknownException",
      "ServiceFailureException",
      "RESTException",
      "NamespaceNotEmptyException",
      "InvalidObjectException",
      "InvalidOperationException",
      "TException",
      "TApplicationException",
      "UncheckedSQLException",
      "GoogleJsonResponseException",
      "SQLIntegrityConstraintViolationException"),
  CONCURRENCY_CONFLICT(
      "CommitFailedException",
      "ConcurrentModificationException",
      "LockException",
      "WaitingForLockException",
      "VersionMismatchException",
      "SQLTransactionRollbackException"),
  SCHEMA_UNSUPPORTED("ValidationException"),
  RESOURCE_EXHAUSTED("OutOfMemoryError", "ResourceNumberLimitExceededException"),
  UNSUPPORTED_FEATURE("UnsupportedOperationException", "SQLFeatureNotSupportedException"),
  CANCELED("UncheckedInterruptedException"),
  INTERNAL_ERROR(
      "NoClassDefFoundError",
      "ClassNotFoundException",
      "NullPointerException",
      "AssertionError",
      "IllegalStateException",
      "IllegalTaskStateException"),
  /** Fallback for failures not mapped above, as olake errs.Unclassified. */
  UNCLASSIFIED;

  /** Exception simple class name to category. */
  private static final Map<String, FailureCategory> BY_EXCEPTION = new HashMap<>();

  static {
    for (FailureCategory category : values()) {
      for (String exception : category.exceptions) {
        BY_EXCEPTION.put(exception, category);
      }
    }
  }

  private final String[] exceptions;

  FailureCategory(String... exceptions) {
    this.exceptions = exceptions;
  }

  /** The category of an exception simple class name, or null when it has none. */
  static FailureCategory forName(String simpleName) {
    return BY_EXCEPTION.get(simpleName);
  }

  /**
   * The category of the exception's class, or of the nearest superclass that has one, or null when
   * none has.
   */
  static FailureCategory of(Throwable t) {
    for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
      FailureCategory category = forName(c.getSimpleName());
      if (category != null) {
        return category;
      }
    }
    return null;
  }
}
