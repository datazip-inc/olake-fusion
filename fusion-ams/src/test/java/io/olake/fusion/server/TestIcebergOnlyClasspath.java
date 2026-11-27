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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Fusion is compaction for Iceberg tables only: no other table format may reach the server. */
public class TestIcebergOnlyClasspath {

  private static final String[] NON_ICEBERG_CLASSES = {
    "org.apache.amoro.hive.catalog.MixedHiveCatalog",
    "org.apache.amoro.mixed.CatalogLoader",
    "org.apache.amoro.formats.paimon.PaimonCatalogFactory",
    "org.apache.amoro.formats.hudi.HudiCatalogFactory",
    "org.apache.amoro.spark.MixedFormatSparkExtensions"
  };

  @Test
  public void nonIcebergFormatClassesAreNotOnTheServerClasspath() {
    for (String className : NON_ICEBERG_CLASSES) {
      Assertions.assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName(className),
          className + " must not be on the server classpath");
    }
  }
}
