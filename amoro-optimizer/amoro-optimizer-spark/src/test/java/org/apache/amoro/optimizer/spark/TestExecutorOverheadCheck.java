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

import org.apache.spark.SparkConf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestExecutorOverheadCheck {

  @Test
  public void executorMemoryDefaultsTo1Gb() {
    Assertions.assertEquals(1024, ExecutorOverheadCheck.executorMemoryMb(new SparkConf(false)));
  }

  @Test
  public void sizesWithoutAUnitAreMiB() {
    SparkConf conf =
        new SparkConf(false)
            .set("spark.executor.memory", "1536")
            .set("spark.executor.memoryOverhead", "512");
    Assertions.assertEquals(1536, ExecutorOverheadCheck.executorMemoryMb(conf));
    Assertions.assertEquals(512, ExecutorOverheadCheck.overheadMb(conf));
  }

  @Test
  public void overheadUsesTheDefaultFactorWhenNotSet() {
    SparkConf conf = new SparkConf(false).set("spark.executor.memory", "10000m");
    Assertions.assertEquals(1000, ExecutorOverheadCheck.overheadMb(conf));
  }

  @Test
  public void overheadUsesTheConfiguredFactor() {
    SparkConf conf =
        new SparkConf(false)
            .set("spark.executor.memory", "10000m")
            .set("spark.executor.memoryOverheadFactor", "0.2");
    Assertions.assertEquals(2000, ExecutorOverheadCheck.overheadMb(conf));
  }

  @Test
  public void explicitOverheadOverridesTheFactor() {
    SparkConf conf =
        new SparkConf(false)
            .set("spark.executor.memory", "10000m")
            .set("spark.executor.memoryOverhead", "256m")
            .set("spark.executor.memoryOverheadFactor", "0.2");
    Assertions.assertEquals(256, ExecutorOverheadCheck.overheadMb(conf));
  }

  @Test
  public void fixSetsTheFactorWhenTheOverheadIsNotExplicit() {
    SparkConf conf = new SparkConf(false).set("spark.executor.memory", "3g");
    Assertions.assertEquals(
        "spark.executor.memoryOverheadFactor=0.15", ExecutorOverheadCheck.overheadFix(conf, 460));
  }

  @Test
  public void fixSetsTheOverheadWhenItIsExplicitBecauseSparkIgnoresTheFactor() {
    SparkConf conf =
        new SparkConf(false)
            .set("spark.executor.memory", "2g")
            .set("spark.executor.memoryOverhead", "128m")
            .set("spark.executor.memoryOverheadFactor", "0.15");
    Assertions.assertEquals(
        "spark.executor.memoryOverhead=384m", ExecutorOverheadCheck.overheadFix(conf, 384));
  }
}
