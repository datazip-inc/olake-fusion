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

package io.olake.fusion.it.harness;

import io.olake.fusion.it.contract.TableProps;

/**
 * File size classes from the public table properties: a fragment is at most {@code target-size /
 * fragment-ratio}; an undersized segment is above that and at most {@code target-size *
 * min-target-size-ratio}; anything larger has reached the target. Each class maps to a row count
 * that lands one Spark-written file in its band; {@link SparkTable} checks the band after every
 * write, so a sizing drift fails as a setup error.
 */
public enum SizeClass {
  FRAGMENT(60),
  UNDERSIZED_SEGMENT(600),
  TARGET_REACHED(3000);

  private final int rows;

  SizeClass(int rows) {
    this.rows = rows;
  }

  public int rows() {
    return rows;
  }

  public static long fragmentMax(long targetSize) {
    return targetSize / TableProps.DEFAULT_FRAGMENT_RATIO;
  }

  public static long undersizedMax(long targetSize) {
    return (long) (targetSize * TableProps.DEFAULT_MIN_TARGET_SIZE_RATIO);
  }

  public static SizeClass of(long fileSize, long targetSize) {
    if (fileSize <= fragmentMax(targetSize)) {
      return FRAGMENT;
    }
    return fileSize <= undersizedMax(targetSize) ? UNDERSIZED_SEGMENT : TARGET_REACHED;
  }
}
