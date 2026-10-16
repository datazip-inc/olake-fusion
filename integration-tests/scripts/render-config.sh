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

# Renders build/config.yaml: the shipped AMS config with docker/fusion-config.overlay.yaml
# merged over it. Nothing is committed as a copy, so the tests always run the shipped
# defaults for every key the overlay does not set.

set -euo pipefail

IT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$IT_DIR/.." && pwd)"
YQ_IMAGE="${YQ_IMAGE:-mikefarah/yq:4}"

mkdir -p "$IT_DIR/build"
docker run --rm -v "$REPO_DIR:/work:ro" -w /work "$YQ_IMAGE" eval-all \
  'select(fileIndex == 0) * select(fileIndex == 1)' \
  dist/src/main/amoro-bin/conf/config.yaml \
  integration-tests/docker/fusion-config.overlay.yaml \
  > "$IT_DIR/build/config.yaml"
echo "Rendered $IT_DIR/build/config.yaml"
