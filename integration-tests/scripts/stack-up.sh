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

# Starts the stack and waits until the bootstrap has created the optimizer group, the
# optimizer and the catalog, and the Spark Connect port accepts connections.
# Expects fusion:it and fusion-spark:it to exist and build/config.yaml to be rendered.

set -euo pipefail

DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docker" && pwd)"
export IT_LOG_DIR="${IT_LOG_DIR:-/tmp/fusion-it-logs}"
SPARK_CONNECT_PORT="${IT_SPARK_CONNECT_PORT:-15002}"

mkdir -p "$IT_LOG_DIR/compaction"
chmod -R 777 "$IT_LOG_DIR"

compose() { docker compose -f "$DOCKER_DIR/docker-compose.yml" "$@"; }

INIT=fusion-it-init

compose build spark-connect
compose up -d

echo "Waiting for $INIT to finish..."
state="missing -1"
for _ in $(seq 1 360); do
  state="$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$INIT" 2>/dev/null || echo 'missing -1')"
  case "$state" in
    "exited 0")
      docker logs "$INIT"
      break
      ;;
    exited*)
      docker logs "$INIT" || true
      compose logs kind-setup kind-load-image fusion || true
      echo "$INIT failed: $state" >&2
      exit 1
      ;;
  esac
  # fusion-init only waits for Fusion to start, so a crashed AMS would otherwise cost
  # fusion-init's whole wait before anything fails.
  ams="$(docker inspect -f '{{.State.Status}}' fusion-it-ams 2>/dev/null || echo missing)"
  if [[ "$ams" == "exited" || "$ams" == "dead" ]]; then
    compose logs fusion || true
    echo "Fusion AMS container stopped: $ams" >&2
    exit 1
  fi
  sleep 5
done
if [[ "$state" != "exited 0" ]]; then
  compose logs || true
  echo "Timed out waiting for $INIT." >&2
  exit 1
fi

echo "Waiting for Spark Connect on port $SPARK_CONNECT_PORT..."
for _ in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$SPARK_CONNECT_PORT") 2>/dev/null; then
    echo "Stack is up."
    exit 0
  fi
  sleep 2
done
compose logs spark-connect || true
echo "Spark Connect did not open port $SPARK_CONNECT_PORT." >&2
exit 1
