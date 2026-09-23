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

CREATE TABLE IF NOT EXISTS table_configurations
(
    catalog_name             VARCHAR(64) NOT NULL,
    db_name                  VARCHAR(128) NOT NULL,
    table_name               VARCHAR(256) NOT NULL,
    self_optimizing_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    minor_trigger_cron       VARCHAR(128) DEFAULT NULL,
    major_trigger_cron       VARCHAR(128) DEFAULT NULL,
    full_trigger_cron        VARCHAR(128) DEFAULT NULL,
    target_size              BIGINT NOT NULL DEFAULT 536870912,
    olake_created            BOOLEAN DEFAULT NULL,
    health_score             INTEGER DEFAULT NULL,
    health_score_snapshot_id BIGINT DEFAULT NULL,
    create_time              TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (catalog_name, db_name, table_name)
);
