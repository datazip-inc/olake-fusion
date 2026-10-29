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

package io.olake.fusion.server;

import java.util.Map;

/** Util method to help get system directories. */
public class Environments {

  /** Environment variable to set HOME dir. */
  public static final String FUSION_HOME = "FUSION_HOME";

  /** Environment variable to set config dir. */
  public static final String FUSION_CONF_DIR = "FUSION_CONF_DIR";

  /** Environment variable to set PLUGIN dir. */
  public static final String FUSION_PLUGIN_DIR = "FUSION_PLUGIN_DIR";

  /** Legacy name of {@link #FUSION_HOME}, still set by existing deployments. */
  @Deprecated public static final String AMORO_HOME = "AMORO_HOME";

  /** Legacy name of {@link #FUSION_CONF_DIR}, still set by OLake-Helm. */
  @Deprecated public static final String AMORO_CONF_DIR = "AMORO_CONF_DIR";

  /** Legacy name of {@link #FUSION_PLUGIN_DIR}. */
  @Deprecated public static final String AMORO_PLUGIN_DIR = "AMORO_PLUGIN_DIR";

  /** Older legacy name of {@link #FUSION_HOME}. */
  @Deprecated public static final String LEGACY_ENV_AMORO_HOME = "ARCTIC_HOME";

  /** Default directory name of config dir. */
  public static final String DEFAULT_CONFIG_DIR_NAME = "conf";

  /** Default directory name of plugin dir. */
  public static final String DEFAULT_PLUGIN_DIR_NAME = "plugin";

  private static final String PROPERTY_USER_DIR = "user.dir";

  /**
   * Get the home path which contains sub-directories of `/bin`, `/conf`, `/plugins` and so on.
   *
   * @return Get the home path
   */
  public static String getHomePath() {
    return getHomePath(System.getenv());
  }

  /**
   * Get the config path which is a directory contains `config.yaml`, `jvm.properties`, `log4j2.xml`
   * and so on.
   *
   * @return The config path
   */
  public static String getConfigPath() {
    return getConfigPath(System.getenv());
  }

  /**
   * Get the path of plugins which is a directory contains jars of plugins.
   *
   * @return The plugin path
   */
  public static String getPluginPath() {
    String pluginDir = firstSet(System.getenv(), FUSION_PLUGIN_DIR, AMORO_PLUGIN_DIR);
    if (pluginDir != null) {
      return pluginDir;
    }
    return getHomePath() + "/" + DEFAULT_PLUGIN_DIR_NAME;
  }

  /** Home path resolved from the given environment; Fusion names win over legacy Amoro names. */
  public static String getHomePath(Map<String, String> env) {
    String home = firstSet(env, FUSION_HOME, LEGACY_ENV_AMORO_HOME, AMORO_HOME);
    if (home != null) {
      return home;
    }
    home = System.getProperty(FUSION_HOME, System.getProperty(AMORO_HOME));
    if (home != null) {
      return home;
    }
    return System.getProperty(PROPERTY_USER_DIR);
  }

  /** Config path resolved from the given environment; Fusion names win over legacy Amoro names. */
  public static String getConfigPath(Map<String, String> env) {
    String confDir = firstSet(env, FUSION_CONF_DIR, AMORO_CONF_DIR);
    if (confDir != null) {
      return confDir;
    }
    return getHomePath(env) + "/" + DEFAULT_CONFIG_DIR_NAME;
  }

  private static String firstSet(Map<String, String> env, String... names) {
    for (String name : names) {
      String value = env.get(name);
      if (value != null) {
        return value;
      }
    }
    return null;
  }
}
