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

-- Drops the tables of features Fusion no longer has. Nothing reads or writes them any more:
--   database_metadata, table_metadata: the internal 'ams' catalog
--   table_blocker: table blockers, created only through the removed Thrift table service
--   table_process_state: the removed process/action framework
--   platform_file: uploaded Hadoop site, keytab and krb5 files
--   ha_lease: never used by Fusion
-- The catalog_metadata counters only counted databases and tables of the internal catalog.
DROP TABLE IF EXISTS database_metadata;
DROP TABLE IF EXISTS table_metadata;
DROP TABLE IF EXISTS table_blocker;
DROP TABLE IF EXISTS table_process_state;
DROP TABLE IF EXISTS platform_file;
DROP TABLE IF EXISTS ha_lease;
ALTER TABLE catalog_metadata DROP COLUMN IF EXISTS database_count;
ALTER TABLE catalog_metadata DROP COLUMN IF EXISTS table_count;
