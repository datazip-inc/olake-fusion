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

import io.olake.fusion.FusionTable;
import org.apache.iceberg.HasTableOperations;

/**
 * The Iceberg table format versions that snapshot expiry and compaction run on. A run on a table
 * above {@link #MAX_SUPPORTED} fails when its cron fires.
 */
public final class IcebergFormatVersions {

  /** Highest Iceberg table format version that expiry and compaction run on. */
  public static final int MAX_SUPPORTED = 2;

  private IcebergFormatVersions() {}

  /**
   * Why runs cannot start on {@code table}, or {@code null} when its format version is supported.
   */
  public static String unsupportedReason(FusionTable table) {
    int formatVersion = formatVersion(table);
    return formatVersion > MAX_SUPPORTED
        ? String.format("Iceberg format version %d is not supported right now", formatVersion)
        : null;
  }

  /** The table's Iceberg format version, or 0 when it is not an Iceberg table. */
  private static int formatVersion(FusionTable table) {
    if (!(table.table() instanceof HasTableOperations)) {
      return 0;
    }
    return ((HasTableOperations) table.table()).operations().current().formatVersion();
  }
}
