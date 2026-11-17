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

package org.apache.amoro.server.utils;

/**
 * A task that failed on an optimizer. The optimizer sends AMS only the printed stack trace of its
 * failure, not the exception, so this carries that trace to report the failure like any other.
 */
public class OptimizerTaskException extends RuntimeException {

  public OptimizerTaskException(String stackTrace) {
    // The local stack trace is of no use: the failure happened on the optimizer.
    super(stackTrace, null, false, false);
  }
}
