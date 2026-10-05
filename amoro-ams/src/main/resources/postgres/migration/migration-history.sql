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

-- Bookkeeping for the migrations AMS applies at startup. Created before anything else and never
-- itself recorded as a migration.
CREATE TABLE IF NOT EXISTS ams_schema_migration
(
    version     INT PRIMARY KEY,
    script_name VARCHAR(256) NOT NULL,
    checksum    VARCHAR(64),
    applied_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE ams_schema_migration IS 'Schema migrations already applied to this database';
COMMENT ON COLUMN ams_schema_migration.checksum IS 'SHA-256 of the script when it was applied';
