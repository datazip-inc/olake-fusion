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

import org.apache.amoro.shade.guava32.com.google.common.io.Resources;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** The {@code snapshot_expiry_process} migration as the server applies it on Postgres. */
public class TestSnapshotExpiryMigration extends PostgresTestBase {

  @Test
  public void migrationIsIdempotent() throws Exception {
    // The base class already applied V2; running the script again must not fail or duplicate.
    String script =
        new String(
            Resources.toByteArray(
                Resources.getResource("postgres/migration/V2__snapshot_expiry_process.sql")),
            StandardCharsets.UTF_8);
    execute(script);
    execute(script);

    Assert.assertEquals(
        1,
        query(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
                    + " AND table_name = 'snapshot_expiry_process'")
            .size());
    Assert.assertEquals(
        1,
        query(
                "SELECT 1 FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = 'snapshot_expiry_process_table_index'")
            .size());
  }
}
