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

package org.apache.amoro.server.bootstrap;

import org.apache.amoro.config.Configurations;
import org.apache.amoro.resource.ResourceGroup;
import org.apache.amoro.server.AmoroManagementConf;
import org.apache.amoro.server.DefaultOptimizingService;
import org.apache.amoro.server.resource.OptimizerManager;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// On AMS startup, makes sure the configured optimizer group exists and is an external group.
// The optimizer itself is deployed and kept alive by Kubernetes, so AMS neither starts nor stops
// it: the optimizer pod registers into this group on its own, and a pod that Kubernetes restarts
// registers again.
public class OptimizerBootstrap {
  private static final Logger LOG = LoggerFactory.getLogger(OptimizerBootstrap.class);

  private final Configurations serviceConfig;
  private final OptimizerManager optimizerManager;
  private final DefaultOptimizingService optimizingService;

  public OptimizerBootstrap(
      Configurations serviceConfig,
      OptimizerManager optimizerManager,
      DefaultOptimizingService optimizingService) {
    this.serviceConfig = serviceConfig;
    this.optimizerManager = optimizerManager;
    this.optimizingService = optimizingService;
  }

  public void run() {
    String optimizerGroupName =
        serviceConfig.getString(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_GROUP_NAME);
    if (StringUtils.isBlank(optimizerGroupName)) {
      LOG.info("Optimizer bootstrap is not configured (group-name missing), skipping.");
      return;
    }
    // The single-argument builder makes this an external group, so that optimizers which this ams
    // did not create are allowed to register into it.
    ResourceGroup optimizerGroup = new ResourceGroup.Builder(optimizerGroupName).build();
    ResourceGroup existingOptimizerGroup = optimizerManager.getResourceGroup(optimizerGroupName);
    if (existingOptimizerGroup == null) {
      optimizerManager.createResourceGroup(optimizerGroup);
      optimizingService.createResourceGroup(optimizerGroup);
      LOG.info("Created optimizer group {}", optimizerGroupName);
    } else if (!StringUtils.equals(
        existingOptimizerGroup.getContainer(), optimizerGroup.getContainer())) {
      // An optimizer group left over from an ams that started the optimizer itself.
      optimizerManager.updateResourceGroup(optimizerGroup);
      optimizingService.updateResourceGroup(optimizerGroup);
      LOG.info(
          "Updated optimizer group {} from container {} to {}",
          optimizerGroupName,
          existingOptimizerGroup.getContainer(),
          optimizerGroup.getContainer());
    }
  }
}
