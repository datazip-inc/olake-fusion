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

package io.olake.fusion.it.contract;

/** Table property keys a user sets to configure and trigger optimization. */
public final class TableProps {
  public static final String MINOR_CRON = "self-optimizing.minor.trigger.cron";
  public static final String MAJOR_CRON = "self-optimizing.major.trigger.cron";
  public static final String FULL_CRON = "self-optimizing.full.trigger.cron";

  public static final String TARGET_SIZE = "self-optimizing.target-size";
  public static final String FRAGMENT_RATIO = "self-optimizing.fragment-ratio";
  public static final String MIN_TARGET_SIZE_RATIO = "self-optimizing.min-target-size-ratio";

  /** Shipped defaults of the two ratios (docs/user-guides/configurations.md). */
  public static final int DEFAULT_FRAGMENT_RATIO = 8;

  public static final double DEFAULT_MIN_TARGET_SIZE_RATIO = 0.75;

  /** Cron key of an optimizing type. */
  public static String cronOf(String optimizingType) {
    switch (optimizingType) {
      case OptimizingType.MINOR:
        return MINOR_CRON;
      case OptimizingType.MAJOR:
        return MAJOR_CRON;
      case OptimizingType.FULL:
        return FULL_CRON;
      default:
        throw new IllegalArgumentException("unknown optimizing type " + optimizingType);
    }
  }

  private TableProps() {}
}
