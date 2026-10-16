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

# The single entry point of the integration tests, used by `make it` locally and in CI.
#
#   FUSION_IMAGE, FUSION_SPARK_IMAGE  images under test; built from the working tree when unset
#   OLAKE_IMAGE                       OLake Postgres source image (default olakego/source-postgres:latest)
#   IT_KEEP_DATA=1                    keep the stack and the test data after the run
#   IT_ONLY=<test>                    run one test method, e.g. minorCompaction
#   IT_PARALLELISM                    tests run at the same time (default 2)
#   IT_RUN_ID                         suffix for every name the run creates (default: derived from the clock)
#   IT_LOG_DIR                        host dir for AMS/optimizer logs (default /tmp/fusion-it-logs)

set -euo pipefail

IT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$IT_DIR/.." && pwd)"
export OLAKE_IMAGE="${OLAKE_IMAGE:-olakego/source-postgres:latest}"
export IT_RUN_ID="${IT_RUN_ID:-$(date +%s | tail -c 7)}"
export IT_LOG_DIR="${IT_LOG_DIR:-/tmp/fusion-it-logs}"

timing() { echo "[timing] it.sh $1: $(( $(date +%s) - $2 ))s"; }
cd "$REPO_DIR"

if pgrep -f MavenWrapperMain >/dev/null; then
  echo "Another Maven build is running; concurrent builds corrupt target/. Stop it first." >&2
  exit 1
fi

# 1. Images under test: pinned when given, otherwise built from the working tree once.
t=$(date +%s)
if [[ -n "${FUSION_IMAGE:-}" && -n "${FUSION_SPARK_IMAGE:-}" ]]; then
  docker tag "$FUSION_IMAGE" fusion:it
  docker tag "$FUSION_SPARK_IMAGE" fusion-spark:it
else
  "$IT_DIR/scripts/build.sh" artifacts
  "$IT_DIR/scripts/build.sh" images
fi
docker image inspect "$OLAKE_IMAGE" >/dev/null 2>&1 || docker pull "$OLAKE_IMAGE"
timing images "$t"

# 2-5. A fresh stack, so nothing from an earlier run leaks in.
t=$(date +%s)
"$IT_DIR/scripts/stack-down.sh"
"$IT_DIR/scripts/render-config.sh"
"$IT_DIR/scripts/stack-up.sh"
timing stack-up "$t"

# 6. The suite: parallel tests, then the serial ones.
t=$(date +%s)
status=0
mvn_args=(-B -ntp -f "$IT_DIR/pom.xml" verify "-Dit.parallelism=${IT_PARALLELISM:-2}")
if [[ -n "${IT_ONLY:-}" ]]; then
  mvn_args+=("-Dit.test=FusionIT#${IT_ONLY}" -Dfailsafe.failIfNoSpecifiedTests=false)
fi
./mvnw "${mvn_args[@]}" || status=$?
timing verify "$t"

# 7. Logs, always.
"$IT_DIR/scripts/collect-logs.sh" "$IT_DIR/target/it-logs"

# 8. Tear down only when the run passed and the data need not be kept.
if [[ $status -eq 0 && "${IT_KEEP_DATA:-0}" != "1" ]]; then
  "$IT_DIR/scripts/stack-down.sh"
else
  echo "Stack kept for inspection (status=$status, IT_KEEP_DATA=${IT_KEEP_DATA:-0}). Remove it with: make it-down"
fi
exit $status
