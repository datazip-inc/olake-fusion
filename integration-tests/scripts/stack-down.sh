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

# Tears down the integration test stack, including the Kind cluster that
# kind-setup created through the host Docker socket.

set -uo pipefail

DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docker" && pwd)"

docker compose -f "$DOCKER_DIR/docker-compose.yml" down -v --remove-orphans

nodes="$(docker ps -aq --filter "label=io.x-k8s.kind.cluster=fusion-it-cluster")"
if [[ -n "$nodes" ]]; then
  docker rm -f $nodes
fi
docker network rm fusion-it-net 2>/dev/null || true
