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
  /** Creates an API token for the logged-in user. */
  public static final String API_TOKEN_CREATE = "/api/token/create";
  /** Query parameters of a token-signed request. */
  public static final String API_KEY_PARAM = "apiKey";

  public static final String SIGNATURE_PARAM = "signature";
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

  public static final String RESOURCE_GROUPS = "/optimize/resourceGroups";
  /** optimizer group. */
  public static final String OPTIMIZERS = "/optimize/optimizerGroups/%s/optimizers";

  public static final String OVERVIEW_TOP = "/overview/top";

  /** catalog. Runs a SQL script in Fusion's terminal, as OLake-UI does for table settings. */
  public static final String TERMINAL_EXECUTE = "/terminal/catalogs/%s/execute";
  /** terminal session id. */
  public static final String TERMINAL_LOGS = "/terminal/%s/logs";

  /** Header of a request authenticated by the login session (the UI); token requests omit it. */
  public static final String SOURCE_HEADER = "X-Request-Source";

  public static final String SOURCE_HEADER_VALUE = "Web";

  private ApiPaths() {}
}
