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

# The user's query engine: a Spark Connect server with Iceberg. Versions are pinned
# here, independent of Fusion's own Spark and Iceberg versions.

ARG SPARK_VERSION=3.5.8
FROM apache/spark:${SPARK_VERSION}-java17

ARG SPARK_VERSION=3.5.8
ARG SCALA_BINARY=2.12
ARG ICEBERG_VERSION=1.10.2
ARG POSTGRES_JDBC_VERSION=42.7.4
ARG MAVEN_MIRROR=https://repo.maven.apache.org/maven2

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

RUN cd $SPARK_HOME/jars \
    && wget -q ${MAVEN_MIRROR}/org/apache/spark/spark-connect_${SCALA_BINARY}/${SPARK_VERSION}/spark-connect_${SCALA_BINARY}-${SPARK_VERSION}.jar \
    && wget -q ${MAVEN_MIRROR}/org/apache/iceberg/iceberg-spark-runtime-3.5_${SCALA_BINARY}/${ICEBERG_VERSION}/iceberg-spark-runtime-3.5_${SCALA_BINARY}-${ICEBERG_VERSION}.jar \
    && wget -q ${MAVEN_MIRROR}/org/apache/iceberg/iceberg-aws-bundle/${ICEBERG_VERSION}/iceberg-aws-bundle-${ICEBERG_VERSION}.jar \
    && wget -q ${MAVEN_MIRROR}/org/postgresql/postgresql/${POSTGRES_JDBC_VERSION}/postgresql-${POSTGRES_JDBC_VERSION}.jar \
    && chown spark:spark *.jar

COPY integration-tests/docker/spark-connect/spark-defaults.conf $SPARK_HOME/conf/spark-defaults.conf

USER spark
EXPOSE 15002
ENTRYPOINT ["/bin/bash", "-c", "$SPARK_HOME/sbin/start-connect-server.sh --master 'local[2]' && tail -F $SPARK_HOME/logs/*"]
