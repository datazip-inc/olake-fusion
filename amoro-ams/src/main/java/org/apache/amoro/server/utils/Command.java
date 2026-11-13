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

/**
 * An operation Fusion performs, sent as an event's {@code command} property. Enum names are sent
 * lower-cased: CREATE_CATALOG -> create_catalog. Failure events use it as the OLake connector's
 * TrackFailure does (olake utils/telemetry), with a category from {@link FailureCategory}.
 */
public enum Command {
  CREATE_CATALOG,
  UPDATE_CATALOG,
  DROP_CATALOG,
  // Building a stored catalog when AMS starts.
  LOAD_CATALOG,
  // Table sync, run every explore interval.
  EXPLORE_CATALOG,
  LIST_TABLES,
  // Registering a table newly found in the catalog; dispose is the reverse.
  REGISTER_TABLE,
  DISPOSE_TABLE,
  // Launching an optimizer in its container; the source is the container type, e.g. spark.
  START_OPTIMIZER,
  // A started optimizer registering with AMS.
  REGISTER_OPTIMIZER,
  // Optimizer bookkeeping in AMS, retried on a loop.
  KEEP_OPTIMIZERS,
  RETRY_OPTIMIZING_TASK,
  SYNC_OPTIMIZER_GROUPS,
  // An optimizer that stopped heart-beating, e.g. after a crash or an OOM kill.
  OPTIMIZER_EXPIRED,
  // No optimizer registered within a few minutes of AMS starting, e.g. a pod that never started.
  OPTIMIZER_NOT_REGISTERED,
  // Compaction. Planning is retried on every scheduling round.
  PLAN_OPTIMIZING,
  // A task that failed on the optimizer and used up its retries.
  EXECUTE_OPTIMIZING,
  COMMIT_OPTIMIZING,
  // Table maintenance, run on a schedule per table.
  EXPIRE_SNAPSHOTS,
  REFRESH_TABLE,
  EVALUATE_TABLE_HEALTH
}
