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

package org.apache.amoro.it.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs host commands (docker, kubectl through docker exec) and returns their output. */
public final class Shell {

  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  private Shell() {}

  public static String run(Duration timeout, String... command) {
    return run(timeout, Arrays.asList(command));
  }

  /** Runs the command, fails on a non-zero exit code or timeout, and returns stdout + stderr. */
  public static String run(Duration timeout, List<String> command) {
    LOG.info("$ {}", String.join(" ", command));
    File output = null;
    try {
      output = File.createTempFile("it-shell", ".log");
      Process process =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "Timed out after " + timeout + ": " + command + "\n" + read(output));
      }
      String text = read(output);
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "Exit code " + process.exitValue() + ": " + command + "\n" + text);
      }
      return text;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot run " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted: " + command, e);
    } finally {
      if (output != null && !output.delete()) {
        output.deleteOnExit();
      }
    }
  }

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }
}
