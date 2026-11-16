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

package org.apache.amoro.server.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** Reads and writes {@code snapshot_expiry_process} rows directly, as the schema defines them. */
public abstract class SnapshotExpiryTestBase extends PostgresTestBase {

  protected static void insertRun(
      long processId,
      long tableId,
      String status,
      int retryNumber,
      long createTimeMillis,
      String failMessage,
      String summaryJson)
      throws SQLException {
    execute(
        "INSERT INTO snapshot_expiry_process"
            + " (process_id, table_id, status, retry_number, create_time, finish_time,"
            + " fail_message, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        processId,
        tableId,
        status,
        retryNumber,
        new Timestamp(createTimeMillis),
        "RUNNING".equals(status) ? null : new Timestamp(createTimeMillis + 1000),
        failMessage,
        summaryJson);
  }

  /** The table's runs, oldest first. */
  protected static List<Map<String, Object>> runs(long tableId) throws SQLException {
    return query(
        "SELECT process_id, status, retry_number, fail_message, summary"
            + " FROM snapshot_expiry_process WHERE table_id = ? ORDER BY process_id",
        tableId);
  }
}
