
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#
# Modified by Datazip Inc. in 2026

# Usage:
#   Run the docker command below under project dir.
#      docker build \
#        --file docker/fusion/Dockerfile \
#        --tag apache/amoro:tagname
#        .

FROM eclipse-temurin:17-jdk-jammy AS builder

# Unpack the distribution built by Maven.

ADD . /workspace/fusion
WORKDIR /workspace/fusion

RUN FUSION_VERSION=`cat pom.xml | grep 'fusion-parent' -C 3 | grep -Eo '<version>.*</version>' | awk -F'[><]' '{print $3}'` \
    && cp dist/target/*.tar.gz /usr/local \
    && tar -xzvf /usr/local/fusion-${FUSION_VERSION}-bin.tar.gz -C /usr/local \
    && mv /usr/local/fusion-${FUSION_VERSION} /usr/local/fusion \
    && rm -rf /workspace/fusion


FROM eclipse-temurin:17-jdk-jammy

ENV FUSION_HOME /usr/local/fusion
# No FUSION_CONF_DIR default: an AMORO_CONF_DIR set by OLake-Helm must keep working, and the conf
# dir falls back to ${FUSION_HOME}/conf. OLake-Helm and OLake-UI's compose mount under
# /usr/local/amoro, which links to /usr/local/fusion.
ENV AMORO_HOME ${FUSION_HOME}
ENV LOG_LEVEL info
EXPOSE 1630 1261

COPY ./docker/fusion/entrypoint.sh /
RUN chmod +x /entrypoint.sh
COPY --from=builder "/usr/local/fusion" "/usr/local/fusion"
RUN ln -s /usr/local/fusion /usr/local/amoro

WORKDIR ${FUSION_HOME}

ENTRYPOINT ["/entrypoint.sh"]
CMD ["help"]

