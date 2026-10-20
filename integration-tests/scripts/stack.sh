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

# The test stack: Fusion, its Postgres and MinIO, the Spark optimizer on Kind, and what a user
# brings (a source Postgres for OLake, a Spark Connect query engine).
#
#   stack.sh up            Render the config, start everything, wait until it is usable
#   stack.sh down          Remove the containers, volumes, Kind cluster and network
#   stack.sh logs [dir]    Collect container and Spark pod logs into dir (default: logs)
#
# Expects fusion:test and fusion-spark:test to exist (scripts/build.sh images).

set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$TESTS_DIR/.." && pwd)"
COMPOSE_FILE="$TESTS_DIR/docker/docker-compose.yml"
CLUSTER=fusion-test-cluster
NETWORK=fusion-test-net
INIT=fusion-test-init
AMS=fusion-test-ams
YQ_IMAGE="${YQ_IMAGE:-mikefarah/yq:4}"

export TEST_LOG_DIR="${TEST_LOG_DIR:-/tmp/fusion-test-logs}"
SPARK_CONNECT_PORT="${TEST_SPARK_CONNECT_PORT:-15002}"

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# Renders build/config.yaml, the config.yaml the Fusion container starts with: the shipped AMS
# config with docker/fusion-config.overlay.yaml merged over it. The overlay holds only what the
# test stack needs (database URL, exposed host, the sparkContainer pointing at fusion-spark:test
# and the Kind cluster, short refresh intervals). Merging instead of committing a full copy keeps
# the tests on the shipped defaults for every other key, so a change to the product config is
# tested without editing the tests. yq runs in a container so no local install is needed.
config() {
  mkdir -p "$TESTS_DIR/build"
  docker run --rm -v "$REPO_DIR:/work:ro" -w /work "$YQ_IMAGE" eval-all \
    'select(fileIndex == 0) * select(fileIndex == 1)' \
    dist/src/main/amoro-bin/conf/config.yaml \
    integration-tests/docker/fusion-config.overlay.yaml \
    > "$TESTS_DIR/build/config.yaml"
  echo "Rendered $TESTS_DIR/build/config.yaml"
}

up() {
  set -e
  config
  mkdir -p "$TEST_LOG_DIR/compaction"
  chmod -R 777 "$TEST_LOG_DIR"

  # spark-connect is the only image compose builds itself (Spark with the Iceberg runtime OLake
  # writes with). `up` builds an image only when it is missing, so an edited Dockerfile or
  # spark-defaults.conf would silently keep the old image; building first picks up the change
  # (cached layers make this a no-op otherwise).
  compose build spark-connect
  compose up -d

  echo "Waiting for $INIT to finish..."
  local state="missing -1" ams
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
    ams="$(docker inspect -f '{{.State.Status}}' "$AMS" 2>/dev/null || echo missing)"
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
      return 0
    fi
    sleep 2
  done
  compose logs spark-connect || true
  echo "Spark Connect did not open port $SPARK_CONNECT_PORT." >&2
  exit 1
}

# Removes everything, including the Kind cluster that kind-setup created through the host
# Docker socket and so is not compose's to remove.
down() {
  compose down -v --remove-orphans
  local nodes
  nodes="$(docker ps -aq --filter "label=io.x-k8s.kind.cluster=$CLUSTER")"
  if [[ -n "$nodes" ]]; then
    docker rm -f $nodes
  fi
  docker network rm "$NETWORK" 2>/dev/null || true
}

logs() {
  local out="${1:-logs}"
  mkdir -p "$out/pods"
  compose logs --no-color > "$out/compose.log" 2>&1
  local kubectl=(docker exec "$CLUSTER-control-plane" kubectl
    --kubeconfig /etc/kubernetes/admin.conf -n spark)
  "${kubectl[@]}" get pods -o wide > "$out/pods/pods.txt" 2>&1
  for pod in $("${kubectl[@]}" get pods -o name 2>/dev/null); do
    "${kubectl[@]}" logs "${pod#pod/}" --all-containers > "$out/pods/${pod#pod/}.log" 2>&1
  done
  if [[ -d "$TEST_LOG_DIR" ]]; then
    cp -r "$TEST_LOG_DIR" "$out/optimization-logs" 2>/dev/null
  fi
  return 0
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  logs) logs "${2:-}" ;;
  *) echo "usage: $0 up|down|logs [dir]" >&2; exit 2 ;;
esac
