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

-- optimizing_process_state.target_change_snapshot_id only ever held -1: it is the change store
-- snapshot of mixed-format tables, which Fusion does not support. Fusion no longer writes or reads
-- it, and a fresh database does not create it. Databases created before this release keep the
-- column, so an earlier image that still writes and reads it keeps working; it only gets a default
-- here, so inserts that omit it still satisfy its NOT NULL. Setting a default only changes the
-- catalog and does not rewrite existing rows.
DO $$
BEGIN
    IF EXISTS (SELECT 1
               FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'optimizing_process_state'
                 AND column_name = 'target_change_snapshot_id') THEN
        ALTER TABLE optimizing_process_state ALTER COLUMN target_change_snapshot_id SET DEFAULT -1;
    END IF;
END
$$;
