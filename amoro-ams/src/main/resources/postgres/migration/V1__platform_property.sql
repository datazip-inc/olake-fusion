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

-- Long lived platform level properties, currently the anonymous OLake telemetry install id.
CREATE TABLE IF NOT EXISTS platform_property
(
    property_key   VARCHAR(128) PRIMARY KEY,
    property_value VARCHAR(512) NOT NULL,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform_property IS 'Long lived platform level properties';
COMMENT ON COLUMN platform_property.property_key IS 'Property key';
COMMENT ON COLUMN platform_property.property_value IS 'Property value';
COMMENT ON COLUMN platform_property.create_time IS 'Property create timestamp';
