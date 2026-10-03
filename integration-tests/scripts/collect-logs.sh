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

# Collects container logs and Spark pod logs into $1 (default: ./it-logs).

set -uo pipefail

OUT="${1:-it-logs}"
DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docker" && pwd)"
mkdir -p "$OUT/pods"

docker compose -f "$DOCKER_DIR/docker-compose.yml" logs --no-color > "$OUT/compose.log" 2>&1

KUBECTL=(docker exec fusion-it-cluster-control-plane kubectl --kubeconfig /etc/kubernetes/admin.conf -n spark)
"${KUBECTL[@]}" get pods -o wide > "$OUT/pods/pods.txt" 2>&1
for pod in $("${KUBECTL[@]}" get pods -o name 2>/dev/null); do
  name="${pod#pod/}"
  "${KUBECTL[@]}" logs "$name" --all-containers > "$OUT/pods/$name.log" 2>&1
done

if [[ -d "${IT_LOG_DIR:-/tmp/fusion-it-logs}" ]]; then
  cp -r "${IT_LOG_DIR:-/tmp/fusion-it-logs}" "$OUT/optimization-logs" 2>/dev/null
fi
exit 0
