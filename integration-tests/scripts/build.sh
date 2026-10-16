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

# Builds what the integration tests run against. Two phases, so CI can run them in separate
# jobs and a failure points at the phase that broke:
#
#   build.sh artifacts  Maven: Fusion distribution, Spark optimizer job, and the IT test sources
#                       (a compile error in the tests fails here, before any stack starts)
#   build.sh images     Docker: fusion:it and fusion-spark:it from those artifacts
#
#   SPARK_VERSION, SCALA_VERSION  optimizer build (default 3.5.8 / 2.13.18)

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SPARK_VERSION="${SPARK_VERSION:-3.5.8}"
SCALA_VERSION="${SCALA_VERSION:-2.13.18}"
cd "$REPO_DIR"

artifacts() {
  local scala_binary
  scala_binary="$(echo "$SCALA_VERSION" | cut -d. -f1-2)"
  ./mvnw -B -ntp clean package -pl dist -am -DskipTests \
    -Psupport-all-formats -Pno-extended-disk-storage -Pno-plugin-bin
  ./mvnw -B -ntp package -pl amoro-optimizer/amoro-optimizer-spark -am -DskipTests \
    -Dspark.version="$SPARK_VERSION" -Dscala.version="$SCALA_VERSION" -Dscala.binary.version="$scala_binary"
  ./mvnw -B -ntp -f integration-tests/pom.xml test-compile
}

images() {
  local optimizer_jar
  optimizer_jar="$(ls amoro-optimizer/amoro-optimizer-spark/target/*-jar-with-dependencies.jar | head -1)"
  docker build -f docker/amoro/Dockerfile -t fusion:it .
  docker build -f docker/optimizer-spark/Dockerfile -t fusion-spark:it \
    --build-arg SPARK_VERSION="$SPARK_VERSION" \
    --build-arg SPARK_JAVA_TAG="$SPARK_VERSION-java17" \
    --build-arg OPTIMIZER_JOB="$optimizer_jar" .
}

case "${1:-}" in
  artifacts) artifacts ;;
  images) images ;;
  *) echo "usage: $0 artifacts|images" >&2; exit 2 ;;
esac
