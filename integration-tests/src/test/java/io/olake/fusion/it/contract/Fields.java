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

/** JSON field names served by the REST API. */
public final class Fields {
  // Response envelope.
  public static final String CODE = "code";
  public static final String MESSAGE = "message";
  public static final String RESULT = "result";
  public static final String LIST = "list";

  // Login.
  public static final String USER = "user";
  public static final String PASSWORD = "password";

  // Catalogs, resource groups, tables.
  public static final String CATALOG_NAME = "catalogName";
  public static final String RESOURCE_GROUP = "resourceGroup";
  public static final String NAME = "name";

  // Optimizers.
  public static final String TOUCH_TIME = "touchTime";
  public static final String JOB_STATUS = "jobStatus";
  public static final String CONTAINER = "container";

  // Optimizing processes.
  public static final String PROCESS_ID = "processId";
  public static final String STATUS = "status";
  public static final String OPTIMIZING_TYPE = "optimizingType";
  public static final String START_TIME = "startTime";
  public static final String FINISH_TIME = "finishTime";
  public static final String FAIL_REASON = "failReason";
  public static final String TOTAL_TASKS = "totalTasks";

  // Snapshots tab.
  public static final String SNAPSHOT_ID = "snapshotId";
  public static final String COMMIT_TIME = "commitTime";

  // Partitions and files tabs.
  public static final String PARTITION = "partition";
  public static final String PATH = "path";
  public static final String FILE_TYPE = "fileType";
  public static final String FILE_SIZE = "fileSize";

  // Health score: table details and overview.
  public static final String TABLE_SUMMARY = "tableSummary";
  public static final String HEALTH_SCORE = "healthScore";
  public static final String TABLE_NAME = "tableName";

  private Fields() {}
}
