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

package io.olake.fusion.it.contract;

/** REST paths, relative to {@link #BASE}. Placeholders are filled with {@code String.format}. */
public final class ApiPaths {
  public static final String BASE = "/api/ams/v1";

  public static final String LOGIN = "/login";
  public static final String CURRENT_USER = "/login/current";
  public static final String CATALOGS = "/catalogs";
  /** catalog, database. */
  public static final String TABLES = "/catalogs/%s/databases/%s/tables";
  /** catalog, database, table. */
  public static final String TABLE_DETAILS = "/tables/catalogs/%s/dbs/%s/tables/%s/details";
  /** catalog, database, table. */
  public static final String PROCESSES =
      "/tables/catalogs/%s/dbs/%s/tables/%s/optimizing-processes";
  /** catalog, database, table. */
  public static final String SNAPSHOTS = "/tables/catalogs/%s/dbs/%s/tables/%s/snapshots";
  /** catalog, database, table. */
  public static final String PARTITIONS = "/tables/catalogs/%s/dbs/%s/tables/%s/partitions";
  /** catalog, database, table, partition ({@link #UNPARTITIONED} for an unpartitioned table). */
  public static final String PARTITION_FILES =
      "/tables/catalogs/%s/dbs/%s/tables/%s/partitions/%s/files";
  /** The partition segment the UI sends for an unpartitioned table. */
  public static final String UNPARTITIONED = "null";

  public static final String RESOURCE_GROUPS = "/optimize/resourceGroups";
  /** optimizer group. */
  public static final String OPTIMIZERS = "/optimize/optimizerGroups/%s/optimizers";

  public static final String OVERVIEW_TOP = "/overview/top";

  /** Header every UI request carries. */
  public static final String SOURCE_HEADER = "X-Request-Source";

  public static final String SOURCE_HEADER_VALUE = "Web";

  private ApiPaths() {}
}
