#!/bin/sh
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Modified by Datazip Inc. in 2026
#


CURRENT_DIR="$( cd "$(dirname "$0")" ; pwd -P )"
FUSION_HOME="$( cd "$CURRENT_DIR/../" ; pwd -P )"
export FUSION_HOME

# Legacy AMORO_* names (set by existing OLake-Helm and Docker deployments) are still accepted;
# FUSION_* names win when both are set.
for name in LOG_DIR CONF_DIR LOG_CONF_FILE ADDITION_CLASSPATH; do
    fusion_var="FUSION_${name}"
    amoro_var="AMORO_${name}"
    if [ -z "${!fusion_var}" ] && [ -n "${!amoro_var}" ]; then
        export "${fusion_var}=${!amoro_var}"
    fi
done

if [[ -d $JAVA_HOME ]]; then
    JAVA_RUN=$JAVA_HOME/bin/java
else
    JAVA_RUN=java
fi

if [ -z "$FUSION_LOG_DIR" ]; then
    export FUSION_LOG_DIR="${FUSION_HOME}/logs"
fi

if [ -z "$FUSION_CONF_DIR" ]; then
    export FUSION_CONF_DIR="${FUSION_HOME}/conf"
fi

if [ -z "$FUSION_LOG_CONF_FILE" ]; then
    export FUSION_LOG_CONF_FILE="${FUSION_CONF_DIR}/log4j2.xml"
fi

export FUSION_ENV_FILE=${FUSION_CONF_DIR}/env.sh

JVM_PROPERTIES=${FUSION_CONF_DIR}/jvm.properties
JVM_VALUE=
parseJvmArgs() {
  ARG=$1
  value=$(cat "$JVM_PROPERTIES" | grep "$ARG=" | sed -e "s/$ARG=\(.*\)/\1/")
  value=$(echo "$value" | sed 's/^"\(.*\)"$/\1/')
  JVM_VALUE=$value
}

parseJvmArgs "xmx"
JVM_XMX_CONFIG=${JVM_VALUE}

parseJvmArgs "xms"
JVM_XMS_CONFIG=${JVM_VALUE}

parseJvmArgs "jmx.remote.port"
JMX_REMOTE_PORT_CONFIG=${JVM_VALUE}

parseJvmArgs "extra.options"
JVM_EXTRA_CONFIG=${JVM_VALUE}

export JVM_XMX_CONFIG
export JVM_XMS_CONFIG
export JMX_REMOTE_PORT_CONFIG
export JVM_EXTRA_CONFIG

test -f ${FUSION_ENV_FILE} && source ${FUSION_ENV_FILE}

# set env variable amoro-addition-classpath if not exists
if [ -z "${FUSION_ADDITION_CLASSPATH}" ]; then
    export FUSION_ADDITION_CLASSPATH=
fi

# add hadoop_conf_dir to amoro addition classpath
if [ -n "${HADOOP_CONF_DIR}" ]; then
    export FUSION_ADDITION_CLASSPATH=$FUSION_ADDITION_CLASSPATH:$HADOOP_CONF_DIR
fi

# add hive_conf_dir to amoro addition classpath
if [ -n "${HIVE_CONF_DIR}" ]; then
    export FUSION_ADDITION_CLASSPATH=${FUSION_ADDITION_CLASSPATH}:${HIVE_CONF_DIR}
fi

# Keep the legacy names in sync for anything that still reads them.
export AMORO_HOME="${FUSION_HOME}" AMORO_CONF_DIR="${FUSION_CONF_DIR}" AMORO_LOG_DIR="${FUSION_LOG_DIR}"
