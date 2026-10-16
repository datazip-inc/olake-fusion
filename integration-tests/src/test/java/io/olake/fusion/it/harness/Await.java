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

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Polls an observable signal until it holds or a deadline passes. Never a fixed sleep. */
public final class Await {
  private static final Duration POLL = Duration.ofSeconds(3);

  private Await() {}

  public static <T> T until(
      String description, Duration timeout, Supplier<T> probe, Predicate<T> done) {
    long deadline = System.nanoTime() + timeout.toNanos();
    T last = null;
    RuntimeException lastError = null;
    while (true) {
      try {
        last = probe.get();
        lastError = null;
        if (done.test(last)) {
          return last;
        }
      } catch (RuntimeException e) {
        lastError = e;
      }
      if (System.nanoTime() >= deadline) {
        AssertionError error =
            new AssertionError(
                "Timed out after "
                    + timeout
                    + " waiting for: "
                    + description
                    + ". Last value: "
                    + last);
        if (lastError != null) {
          error.initCause(lastError);
        }
        throw error;
      }
      try {
        Thread.sleep(POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted waiting for " + description, e);
      }
    }
  }
}
