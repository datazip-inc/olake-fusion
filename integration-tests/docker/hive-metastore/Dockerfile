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

# The user's Hive Metastore, for the tests on a Hive catalog: the image and the S3A jars OLake's
# own local Hive setup uses. The metastore's own metadata stays in its embedded Derby database;
# table data and Iceberg metadata live in MinIO. The jars let the metastore create the table
# directories it is given in s3:// and s3a:// locations.

ARG HIVE_VERSION=4.0.0
FROM apache/hive:${HIVE_VERSION}

ARG HADOOP_AWS_VERSION=3.3.4
ARG AWS_SDK_BUNDLE_VERSION=1.12.262
ARG MAVEN_MIRROR=https://repo.maven.apache.org/maven2

USER root
ADD --chown=hive:hive ${MAVEN_MIRROR}/org/apache/hadoop/hadoop-aws/${HADOOP_AWS_VERSION}/hadoop-aws-${HADOOP_AWS_VERSION}.jar /opt/hive/lib/
ADD --chown=hive:hive ${MAVEN_MIRROR}/com/amazonaws/aws-java-sdk-bundle/${AWS_SDK_BUNDLE_VERSION}/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar /opt/hive/lib/
COPY integration-tests/docker/hive-metastore/hive-site.xml /opt/hive/conf/hive-site.xml
USER hive
