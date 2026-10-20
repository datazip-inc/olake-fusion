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

# The single entry point of the integration tests, used by `make integration-test` locally and
# in CI. Builds the images unless they are pinned, starts a fresh stack, runs the suite,
# collects the logs and tears the stack down when the run passed.
#
#   FUSION_IMAGE, FUSION_SPARK_IMAGE  images under test; built from the working tree when unset
#   OLAKE_IMAGE                       OLake Postgres source image (default olakego/source-postgres:latest)
#   TEST_ONLY=<test>                  run one test method, e.g. minorCompaction
#   TEST_PARALLELISM                  tests run at the same time (default 2)
#   TEST_KEEP_DATA=1                  keep the stack and the test data after the run
#   TEST_RUN_ID                       suffix for every name the run creates (default: from the clock)
#   TEST_LOG_DIR                      host dir for AMS/optimizer logs (default /tmp/fusion-test-logs)

set -euo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$TESTS_DIR/.." && pwd)"
export OLAKE_IMAGE="${OLAKE_IMAGE:-olakego/source-postgres:latest}"
export TEST_RUN_ID="${TEST_RUN_ID:-$(date +%s | tail -c 7)}"
export TEST_LOG_DIR="${TEST_LOG_DIR:-/tmp/fusion-test-logs}"

timing() { echo "[timing] $1: $(( $(date +%s) - $2 ))s"; }
cd "$REPO_DIR"

if pgrep -f MavenWrapperMain >/dev/null; then
  echo "Another Maven build is running; concurrent builds corrupt target/. Stop it first." >&2
  exit 1
fi

# 1. Images under test: pinned when given, otherwise built from the working tree once.
t=$(date +%s)
if [[ -n "${FUSION_IMAGE:-}" && -n "${FUSION_SPARK_IMAGE:-}" ]]; then
  docker tag "$FUSION_IMAGE" fusion:test
  docker tag "$FUSION_SPARK_IMAGE" fusion-spark:test
else
  "$TESTS_DIR/scripts/build.sh" artifacts
  "$TESTS_DIR/scripts/build.sh" images
fi
docker image inspect "$OLAKE_IMAGE" >/dev/null 2>&1 || docker pull "$OLAKE_IMAGE"
timing images "$t"

# 2. A fresh stack, so nothing from an earlier run leaks in.
t=$(date +%s)
"$TESTS_DIR/scripts/stack.sh" down
"$TESTS_DIR/scripts/stack.sh" up
timing stack-up "$t"

# 3. The suite: parallel tests, then the serial ones.
t=$(date +%s)
status=0
mvn_args=(-B -ntp -f "$TESTS_DIR/pom.xml" verify "-Dtest.parallelism=${TEST_PARALLELISM:-2}")
if [[ -n "${TEST_ONLY:-}" ]]; then
  mvn_args+=("-Dit.test=FusionIT#${TEST_ONLY}" -Dfailsafe.failIfNoSpecifiedTests=false)
fi
./mvnw "${mvn_args[@]}" || status=$?
timing verify "$t"

# 4. Logs, always.
"$TESTS_DIR/scripts/stack.sh" logs "$TESTS_DIR/target/logs"

# 5. Tear down only when the run passed and the data need not be kept.
if [[ $status -eq 0 && "${TEST_KEEP_DATA:-0}" != "1" ]]; then
  "$TESTS_DIR/scripts/stack.sh" down
else
  echo "Stack kept for inspection (status=$status, TEST_KEEP_DATA=${TEST_KEEP_DATA:-0})."
  echo "Remove it with: make integration-test-down"
fi
exit $status
