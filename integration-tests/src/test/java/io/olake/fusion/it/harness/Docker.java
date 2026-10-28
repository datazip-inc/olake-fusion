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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The docker CLI, with a timeout. */
public final class Docker {
  private Docker() {}

  /** Runs {@code docker <args>} and returns its combined output; fails on a non-zero exit. */
  public static String run(Duration timeout, String... args) {
    List<String> command = new ArrayList<>();
    command.add("docker");
    command.addAll(Arrays.asList(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      // Read while waiting, so a chatty command cannot block on a full pipe.
      StringBuilder out = new StringBuilder();
      Thread reader =
          new Thread(
              () -> {
                try {
                  out.append(
                      new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                  // The process died; its exit code reports the failure.
                }
              });
      reader.start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("timed out after " + timeout + ": " + command);
      }
      reader.join();
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "exit " + process.exitValue() + ": " + command + "\n" + tail(out.toString()));
      }
      return out.toString();
    } catch (IOException e) {
      throw new IllegalStateException("cannot run " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted: " + command, e);
    }
  }

  private static String tail(String output) {
    String[] lines = output.split("\\R");
    int from = Math.max(0, lines.length - 40);
    return String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
  }
}
