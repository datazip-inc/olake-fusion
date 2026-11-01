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

import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hadoop configuration and credentials of a catalog.
 *
 * <p>Instances are Java-serialized inside optimizing task inputs, so the class name, package,
 * {@code serialVersionUID} and the set of fields must not change. The Hadoop site files, the
 * SIMPLE/Kerberos fields and their values are no longer used: Fusion reaches its storage (S3)
 * through the catalog's FileIO properties, and every call runs as the process user.
 */
public class TableMetaStore implements Serializable {
  private static final long serialVersionUID = 1L;

  private static final Logger LOG = LoggerFactory.getLogger(TableMetaStore.class);

  // Share runtime context with same configuration as context is expensive to construct
  private static final ConcurrentHashMap<TableMetaStore, RuntimeContext> RUNTIME_CONTEXT_CACHE =
      new ConcurrentHashMap<>();

  public static final TableMetaStore EMPTY =
      TableMetaStore.builder().withConfiguration(new Configuration()).build();

  public static final String AUTH_METHOD_AK_SK = "ak/sk";

  // Unused, kept so serialized task inputs written by older versions still deserialize.
  private final byte[] metaStoreSite;
  private final byte[] hdfsSite;
  private final byte[] coreSite;
  private final String authMethod;
  private final String hadoopUsername;
  private final byte[] krbKeyTab;
  private final byte[] krbConf;
  private final String krbPrincipal;
  private final boolean disableAuth;
  private final String accessKey;
  private final String secretKey;
  private final String storageType;

  private transient RuntimeContext runtimeContext;
  private transient String authInformation;

  public static Builder builder() {
    return new Builder();
  }

  private TableMetaStore(
      String authMethod, String accessKey, String secretKey, String storageType) {
    Preconditions.checkArgument(
        authMethod == null || AUTH_METHOD_AK_SK.equals(authMethod),
        "Error auth method:%s",
        authMethod);
    this.metaStoreSite = null;
    this.hdfsSite = null;
    this.coreSite = null;
    this.authMethod = authMethod;
    this.hadoopUsername = null;
    this.krbKeyTab = null;
    this.krbConf = null;
    this.krbPrincipal = null;
    this.disableAuth = authMethod == null;
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.storageType = storageType;
  }

  private TableMetaStore(Configuration configuration) {
    this.disableAuth = true;
    this.metaStoreSite = null;
    this.hdfsSite = null;
    this.coreSite = null;
    this.authMethod = null;
    this.hadoopUsername = null;
    this.krbKeyTab = null;
    this.krbConf = null;
    this.krbPrincipal = null;
    this.accessKey = null;
    this.secretKey = null;
    this.storageType = null;
    this.runtimeContext = new RuntimeContext();
    runtimeContext.setConfiguration(configuration);
  }

  public String getAuthMethod() {
    return authMethod;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public String getStorageType() {
    return storageType;
  }

  public String getHadoopUsername() {
    return hadoopUsername;
  }

  public synchronized Configuration getConfiguration() {
    return getRuntimeContext().getConfiguration();
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

  private RuntimeContext getRuntimeContext() {
    if (runtimeContext == null) {
      RUNTIME_CONTEXT_CACHE.putIfAbsent(this, new RuntimeContext());
      runtimeContext = RUNTIME_CONTEXT_CACHE.get(this);
    }
    return runtimeContext;
  }

  private String authInformation() {
    if (authInformation == null) {
      authInformation = disableAuth ? "disable authentication" : String.valueOf(authMethod);
    }
    return authInformation;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TableMetaStore that = (TableMetaStore) o;
    return Arrays.equals(metaStoreSite, that.metaStoreSite)
        && Arrays.equals(hdfsSite, that.hdfsSite)
        && Arrays.equals(coreSite, that.coreSite)
        && Objects.equals(authMethod, that.authMethod)
        && Objects.equals(hadoopUsername, that.hadoopUsername)
        && Arrays.equals(krbKeyTab, that.krbKeyTab)
        && Arrays.equals(krbConf, that.krbConf)
        && Objects.equals(krbPrincipal, that.krbPrincipal)
        && Objects.equals(disableAuth, that.disableAuth);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(disableAuth, authMethod, hadoopUsername, krbPrincipal);
    result = 31 * result + Arrays.hashCode(metaStoreSite);
    result = 31 * result + Arrays.hashCode(hdfsSite);
    result = 31 * result + Arrays.hashCode(coreSite);
    result = 31 * result + Arrays.hashCode(krbKeyTab);
    result = 31 * result + Arrays.hashCode(krbConf);
    return result;
  }

  @Override
  public String toString() {
    return authInformation();
  }

  class RuntimeContext {
    private Configuration configuration;

    Configuration getConfiguration() {
      if (configuration == null) {
        configuration = buildConfiguration();
      }
      return configuration;
    }

    private void setConfiguration(Configuration configuration) {
      this.configuration = configuration;
    }

    private Configuration buildConfiguration() {
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

  public static class Builder {
    private String authMethod;
    private String accessKey;
    private String secretKey;
    private String storageType;
    private Configuration configuration;

    public Builder withAkSkAuth(String accessKey, String secretKey, String storageType) {
      this.authMethod = AUTH_METHOD_AK_SK;
      this.accessKey = accessKey;
      this.secretKey = secretKey;
      this.storageType = storageType;
      return this;
    }

    public Builder withConfiguration(Configuration configuration) {
      this.configuration = configuration;
      return this;
    }

    public TableMetaStore build() {
      if (authMethod == null && configuration != null) {
        LOG.info("Build table meta store with local configuration:" + configuration);
        return new TableMetaStore(configuration);
      }
      if (AUTH_METHOD_AK_SK.equals(authMethod)) {
        Preconditions.checkNotNull(accessKey);
        Preconditions.checkNotNull(secretKey);
        Preconditions.checkNotNull(storageType);
      }
      LOG.info("Build table meta store with configurations: authMethod:{}", authMethod);
      return new TableMetaStore(authMethod, accessKey, secretKey, storageType);
    }
  }
}
