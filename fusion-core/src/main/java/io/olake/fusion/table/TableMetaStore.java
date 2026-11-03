/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Datazip Inc. in 2026
 */

package io.olake.fusion.table;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;

import java.util.concurrent.Callable;

/**
 * Hadoop configuration of a catalog, for Iceberg's catalog constructors and the terminal. Storage
 * credentials come from the catalog's FileIO properties.
 */
public class TableMetaStore {

  private Configuration configuration;

  public TableMetaStore() {}

  public TableMetaStore(Configuration configuration) {
    this.configuration = configuration;
  }

  public synchronized Configuration getConfiguration() {
    if (configuration == null) {
      configuration = buildConfiguration();
    }
    return configuration;
  }

  /** Runs the callable as the process user. */
  public <T> T doAs(Callable<T> callable) {
    try {
      return callable.call();
    } catch (Throwable e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new RuntimeException("Run with ugi request failed.", e);
    }
  }

  private static Configuration buildConfiguration() {
    Configuration configuration = new Configuration();
    configuration.set(
        CommonConfigurationKeys.IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_KEY, "true");
    // Enforce configuration resolve resources
    configuration.get(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY);

    // Avoid check hive version
    configuration.set("hive.metastore.schema.verification", "false");

    // It will encounter error(Required table missing : "DBS" in Catalog "" Schema "") when there
    // is not this param
    configuration.set("datanucleus.schema.autoCreateAll", "true");

    return configuration;
  }
}
