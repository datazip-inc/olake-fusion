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

package io.olake.fusion.it;

import java.util.Set;

/**
 * What Fusion promises to its users: API paths, JSON field names, statuses, optimizing types, table
 * property keys and the names of the group and catalog the stack creates.
 *
 * <p>Every name here is copied from Fusion's public surface on purpose. Do not replace any of them
 * with an import or a reference to product code. If Fusion renames one of them, the suite must
 * fail: that failure is the signal this class exists to give. Keep it small; what is in it is what
 * Fusion promises, everything else is harness.
 */
public final class Contract {

  /** Names of what the stack bootstrap creates. */
  public static final class Names {
    public static final String OPTIMIZER_GROUP = "spark-container";
    public static final String CATALOG = "olake_iceberg";
    public static final String ADMIN_USER = "admin";
    public static final String ADMIN_PASSWORD = "password";

    private Names() {}
  }

  /** REST paths, relative to {@link #BASE}. Placeholders are filled with {@code String.format}. */
  public static final class ApiPaths {
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

    /** catalog. Runs a SQL script in Fusion's terminal, as OLake-UI does for table settings. */
    public static final String TERMINAL_EXECUTE = "/terminal/catalogs/%s/execute";
    /** terminal session id. */
    public static final String TERMINAL_LOGS = "/terminal/%s/logs";

    /** Header of a request authenticated by the login session (the UI); token requests omit it. */
    public static final String SOURCE_HEADER = "X-Request-Source";

    public static final String SOURCE_HEADER_VALUE = "Web";

    private ApiPaths() {}
  }

  /** JSON field names served by the REST API. */
  public static final class Fields {
    // Response envelope.
    public static final String CODE = "code";
    public static final String RESULT = "result";
    public static final String LIST = "list";

    // Login.
    public static final String USER = "user";
    public static final String PASSWORD = "password";

    // API token.
    public static final String API_KEY = "apikey";
    public static final String SECRET = "secret";

    // Catalogs, resource groups, tables.
    public static final String CATALOG_NAME = "catalogName";
    public static final String RESOURCE_GROUP = "resourceGroup";
    public static final String NAME = "name";

    // Optimizers.
    public static final String TOUCH_TIME = "touchTime";

    // Optimizing processes.
    public static final String PROCESS_ID = "processId";
    public static final String STATUS = "status";
    public static final String OPTIMIZING_TYPE = "optimizingType";
    public static final String START_TIME = "startTime";
    public static final String FINISH_TIME = "finishTime";
    public static final String FAIL_REASON = "failReason";
    public static final String TOTAL_TASKS = "totalTasks";
    /** Per-process metrics. A SKIPPED process carries {@link #SKIP_REASON} in it. */
    public static final String SUMMARY = "summary";

    public static final String SKIP_REASON = "skipReason";

    // Snapshots tab.
    public static final String SNAPSHOT_ID = "snapshotId";
    public static final String COMMIT_TIME = "commitTime";

    // Health score: table details and overview.
    public static final String TABLE_SUMMARY = "tableSummary";
    public static final String HEALTH_SCORE = "healthScore";

    // Terminal.
    public static final String SQL = "sql";
    public static final String SESSION_ID = "sessionId";
    public static final String LOG_STATUS = "logStatus";
    public static final String LOGS = "logs";

    private Fields() {}
  }

  /** Optimizing types: lite, medium and full compaction. */
  public static final class OptimizingType {
    /** Lite. */
    public static final String MINOR = "MINOR";
    /** Medium. */
    public static final String MAJOR = "MAJOR";
    /** Full. */
    public static final String FULL = "FULL";

    private OptimizingType() {}
  }

  /** Optimizing process statuses. */
  public static final class ProcessStatus {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CLOSED = "CLOSED";
    public static final String CANCELED = "CANCELED";
    public static final String KILLED = "KILLED";
    /** A cron fired but there was nothing to optimize. */
    public static final String SKIPPED = "SKIPPED";

    /** Statuses after which a process never changes again. */
    public static final Set<String> TERMINAL =
        Set.of(SUCCESS, FAILED, CLOSED, CANCELED, KILLED, SKIPPED);

    private ProcessStatus() {}
  }

  /**
   * What Fusion tells a user when a cron fired but there was nothing to optimize. Only the stable
   * part of the sentence is kept: the reason is shown in OLake-UI's run history, so it must stay
   * recognizable.
   */
  public static final class SkipReason {
    public static final String SNAPSHOT_UNCHANGED = "snapshot unchanged";

    private SkipReason() {}
  }

  /** Table property keys a user sets to configure and trigger optimization. */
  public static final class TableProps {
    public static final String ENABLED = "self-optimizing.enabled";

    public static final String MINOR_CRON = "self-optimizing.minor.trigger.cron";
    public static final String MAJOR_CRON = "self-optimizing.major.trigger.cron";
    public static final String FULL_CRON = "self-optimizing.full.trigger.cron";
    /** OLake-UI's "Never" schedule: an empty cron. */
    public static final String NEVER = "";

    public static final String TARGET_SIZE = "self-optimizing.target-size";
    public static final String FRAGMENT_RATIO = "self-optimizing.fragment-ratio";
    public static final String MIN_TARGET_SIZE_RATIO = "self-optimizing.min-target-size-ratio";

    /** Shipped defaults of the two ratios (docs/user-guides/configurations.md). */
    public static final int DEFAULT_FRAGMENT_RATIO = 8;

    public static final double DEFAULT_MIN_TARGET_SIZE_RATIO = 0.75;

    /** Cron key of an optimizing type. */
    public static String cronOf(String optimizingType) {
      switch (optimizingType) {
        case OptimizingType.MINOR:
          return MINOR_CRON;
        case OptimizingType.MAJOR:
          return MAJOR_CRON;
        case OptimizingType.FULL:
          return FULL_CRON;
        default:
          throw new IllegalArgumentException("unknown optimizing type " + optimizingType);
      }
    }

    private TableProps() {}
  }

  private Contract() {}
}
