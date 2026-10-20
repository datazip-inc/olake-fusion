#!/bin/sh
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Modified by Datazip Inc. in 2026

# Serial bootstrap, once per stack: optimizer group, optimizer, catalog. All through the
# public REST API, exactly as a user sets Fusion up. Run by the fusion-init service.
#
#   API                    REST base URL, e.g. http://fusion:1630/api/ams/v1
#   OPTIMIZER_PARALLELISM  optimizer parallelism to scale out

set -e
H='X-Request-Source: Web'

echo "Waiting for Fusion at $API..."
i=0
until curl -sf "$API/versionInfo" >/dev/null 2>&1; do
  i=$((i+1)); [ $i -gt 300 ] && { echo "Fusion did not come up"; exit 1; }
  sleep 2
done

curl -sf -c /tmp/c -X POST "$API/login" -H "$H" -H 'Content-Type: application/json' \
  -d '{"user":"admin","password":"password"}'
echo

echo "Creating optimizer group spark-container"
curl -sf -b /tmp/c -X POST "$API/optimize/resourceGroups" -H "$H" -H 'Content-Type: application/json' \
  -d '{"name":"spark-container","container":"sparkContainer","properties":{}}'
echo

echo "Scaling out Spark optimizer, parallelism=$OPTIMIZER_PARALLELISM"
curl -sf -b /tmp/c -X POST "$API/optimize/optimizerGroups/spark-container/optimizers" -H "$H" \
  -H 'Content-Type: application/json' -d "{\"parallelism\":$OPTIMIZER_PARALLELISM}"
echo

echo "Creating catalog olake_iceberg"
curl -sf -b /tmp/c -X POST "$API/catalogs" -H "$H" -H 'Content-Type: application/json' -d '{
    "name":"olake_iceberg",
    "type":"custom",
    "optimizerGroup":"spark-container",
    "tableFormatList":["ICEBERG"],
    "storageConfig":{
      "storage.type":"S3",
      "storage.s3.endpoint":"http://10.210.40.5:9000",
      "storage.s3.region":"us-east-1"
    },
    "authConfig":{
      "auth.type":"AKSK",
      "auth.aksk.access-key":"admin",
      "auth.aksk.secret-key":"password"
    },
    "properties":{
      "catalog-impl":"org.apache.iceberg.jdbc.JdbcCatalog",
      "uri":"jdbc:postgresql://10.210.40.20:5432/iceberg",
      "jdbc.user":"iceberg",
      "jdbc.password":"password",
      "jdbc.driver":"org.postgresql.Driver",
      "jdbc.schema-version":"V1",
      "warehouse":"s3://warehouse/olake_iceberg/",
      "s3.path-style-access":"true",
      "io-impl":"org.apache.iceberg.aws.s3.S3FileIO",
      "endpoint":"http://10.210.40.5:9000",
      "access-key-id":"admin",
      "secret-access-key":"password"
    },
    "tableProperties":{}
  }'
echo
echo "Fusion initialised."
