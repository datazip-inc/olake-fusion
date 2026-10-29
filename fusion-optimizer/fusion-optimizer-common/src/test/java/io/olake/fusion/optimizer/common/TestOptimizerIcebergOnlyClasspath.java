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

package io.olake.fusion.optimizer.common;

import org.junit.Assert;
import org.junit.Test;

/** The optimizer rewrites Iceberg tables only: mixed-hive code must not reach its classpath. */
public class TestOptimizerIcebergOnlyClasspath {

  private static final String[] MIXED_HIVE_CLASSES = {
    "org.apache.amoro.hive.catalog.MixedHiveCatalog",
    "org.apache.amoro.hive.optimizing.MixedHiveRewriteExecutor"
  };

  @Test
  public void mixedHiveClassesAreNotOnTheOptimizerClasspath() {
    for (String className : MIXED_HIVE_CLASSES) {
      Assert.assertThrows(
          className + " must not be on the optimizer classpath",
          ClassNotFoundException.class,
          () -> Class.forName(className));
    }
  }
}
