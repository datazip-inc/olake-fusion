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

package org.apache.amoro.optimizer.spark;

/**
 * Entry point under the name used before the rename from Amoro to Fusion. OLake-Helm starts the
 * optimizer with {@code --class org.apache.amoro.optimizer.spark.SparkOptimizer}; keep this until
 * the chart uses {@link io.olake.fusion.optimizer.spark.SparkOptimizer}.
 */
@Deprecated
public class SparkOptimizer {

  private SparkOptimizer() {}

  public static void main(String[] args) throws Exception {
    io.olake.fusion.optimizer.spark.SparkOptimizer.main(args);
  }
}
