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

package io.olake.fusion.utils;

import org.apache.amoro.shade.guava32.com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Maps class names written before the rename from Amoro to Fusion (config files, Postgres rows,
 * serialized task data) to the current classes.
 */
public final class LegacyClassNames {

  private static final String LEGACY_PACKAGE = "org.apache.amoro.";
  private static final String SHADED_PACKAGE = "org.apache.amoro.shade.";
  private static final String PACKAGE = "io.olake.fusion.";

  /** Classes whose simple name changed from Amoro* to Fusion*; Thrift-generated names did not. */
  private static final Map<String, String> RENAMED_CLASSES =
      ImmutableMap.<String, String>builder()
          .put("AmoroCatalog", "FusionCatalog")
          .put("AmoroIOException", "FusionIOException")
          .put("AmoroManagementConf", "FusionManagementConf")
          .put("AmoroManagementConfValidator", "FusionManagementConfValidator")
          .put("AmoroPlugin", "FusionPlugin")
          .put("AmoroProcess", "FusionProcess")
          .put("AmoroRuntimeException", "FusionRuntimeException")
          .put("AmoroServiceConstants", "FusionServiceConstants")
          .put("AmoroServiceContainer", "FusionServiceContainer")
          .put("AmoroSnapshotsOfTable", "FusionSnapshotsOfTable")
          .put("AmoroTable", "FusionTable")
          .put("AmoroZookeeperFactory", "FusionZookeeperFactory")
          .build();

  private LegacyClassNames() {}

  /** The current name of a class written as {@code name}; names already current are unchanged. */
  public static String toCurrent(String name) {
    if (name != null && name.startsWith("[")) {
      // array descriptor, for example "[Lorg.apache.amoro.X;"
      int start = name.lastIndexOf('[') + 1;
      if (name.charAt(start) == 'L' && name.endsWith(";")) {
        String element = name.substring(start + 1, name.length() - 1);
        return name.substring(0, start + 1) + toCurrent(element) + ";";
      }
      return name;
    }
    if (name == null || !name.startsWith(LEGACY_PACKAGE) || name.startsWith(SHADED_PACKAGE)) {
      return name;
    }
    String rest = name.substring(LEGACY_PACKAGE.length());
    int lastDot = rest.lastIndexOf('.');
    String pkg = lastDot < 0 ? "" : rest.substring(0, lastDot + 1);
    String simple = lastDot < 0 ? rest : rest.substring(lastDot + 1);
    int nested = simple.indexOf('$');
    String outer = nested < 0 ? simple : simple.substring(0, nested);
    String suffix = nested < 0 ? "" : simple.substring(nested);
    return PACKAGE + pkg + RENAMED_CLASSES.getOrDefault(outer, outer) + suffix;
  }
}
