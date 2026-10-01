#!/usr/bin/env bash
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

# Brings up the integration test stack and waits until fusion-init has
# created the optimizer group, the optimizer and the catalog.
#
#   FUSION_IMAGE        AMS image to test        (default: fusion:it)
#   FUSION_SPARK_IMAGE  Spark optimizer image    (default: fusion-spark:it)
#   IT_LOG_DIR          host dir for AMS/Spark optimization logs (default: /tmp/fusion-it-logs)

set -euo pipefail

DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docker" && pwd)"
FUSION_IMAGE="${FUSION_IMAGE:-fusion:it}"
FUSION_SPARK_IMAGE="${FUSION_SPARK_IMAGE:-fusion-spark:it}"
export IT_LOG_DIR="${IT_LOG_DIR:-/tmp/fusion-it-logs}"

[[ "$FUSION_IMAGE" == "fusion:it" ]] || docker tag "$FUSION_IMAGE" fusion:it
[[ "$FUSION_SPARK_IMAGE" == "fusion-spark:it" ]] || docker tag "$FUSION_SPARK_IMAGE" fusion-spark:it

mkdir -p "$IT_LOG_DIR/compaction"
chmod -R 777 "$IT_LOG_DIR"

compose() { docker compose -f "$DOCKER_DIR/docker-compose.yml" "$@"; }

compose up -d

echo "Waiting for fusion-init to finish..."
for _ in $(seq 1 180); do
  state="$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' fusion-it-init 2>/dev/null || echo 'missing -1')"
  case "$state" in
    "exited 0")
      compose logs fusion-init
      echo "Stack is up."
      exit 0
      ;;
    exited*)
      compose logs fusion-init kind-setup kind-load-image fusion || true
      echo "fusion-init failed: $state" >&2
      exit 1
      ;;
  esac
  sleep 5
done

compose logs || true
echo "Timed out waiting for fusion-init." >&2
exit 1
