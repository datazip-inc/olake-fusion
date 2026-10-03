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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** kubectl against the IT Kind cluster, run inside its control-plane container. */
public final class KubeOps {

  private static final String DRIVER_SELECTOR = "spark-role=driver";

  private KubeOps() {}

  /** Names of Spark optimizer driver pods, in any phase. */
  public static List<String> driverPods() {
    String output = kubectl("get", "pods", "-l", DRIVER_SELECTOR, "-o", "name");
    List<String> pods = new ArrayList<>();
    for (String line : output.split("\\R")) {
      if (line.startsWith("pod/")) {
        pods.add(line.substring("pod/".length()).trim());
      }
    }
    return pods;
  }

  /** Kills every Spark optimizer driver pod at once, without a grace period. */
  public static void killDriverPods() {
    kubectl("delete", "pod", "-l", DRIVER_SELECTOR, "--grace-period=0", "--force", "--wait=false");
  }

  public static String kubectl(String... args) {
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                "docker",
                "exec",
                StackEnv.KIND_CONTROL_PLANE,
                "kubectl",
                "--kubeconfig",
                "/etc/kubernetes/admin.conf",
                "-n",
                StackEnv.SPARK_NAMESPACE));
    command.addAll(Arrays.asList(args));
    return Shell.run(Duration.ofMinutes(2), command);
  }
}
