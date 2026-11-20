-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- Modified by Datazip Inc. in 2026

-- One row per snapshot expiry run, kept apart from table_process so expiry stays decoupled from
-- compaction state.
CREATE TABLE IF NOT EXISTS snapshot_expiry_process
(
    process_id   BIGINT PRIMARY KEY,
    table_id     BIGINT NOT NULL,
    status       VARCHAR(64) NOT NULL,
    retry_number INT NOT NULL DEFAULT 0,
    create_time  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finish_time  TIMESTAMPTZ,
    fail_message TEXT CHECK (length(fail_message) <= 4096),
    summary      TEXT
);
CREATE INDEX IF NOT EXISTS snapshot_expiry_process_table_index
    ON snapshot_expiry_process (table_id, process_id);
COMMENT ON TABLE snapshot_expiry_process IS 'Snapshot expiry runs';
COMMENT ON COLUMN snapshot_expiry_process.process_id IS 'Run id (snowflake)';
COMMENT ON COLUMN snapshot_expiry_process.table_id IS 'Table identifier id';
COMMENT ON COLUMN snapshot_expiry_process.status IS 'RUNNING, SUCCESS or FAILED';
COMMENT ON COLUMN snapshot_expiry_process.retry_number IS 'Attempts after the first one';
COMMENT ON COLUMN snapshot_expiry_process.create_time IS 'Run start';
COMMENT ON COLUMN snapshot_expiry_process.finish_time IS 'Run end';
COMMENT ON COLUMN snapshot_expiry_process.fail_message IS 'Why the run failed';
COMMENT ON COLUMN snapshot_expiry_process.summary IS 'Snapshot counts of a successful run, JSON';
