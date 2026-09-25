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
import org.apache.amoro.resource.Resource;
import org.apache.amoro.resource.ResourceContainer;
import org.apache.amoro.resource.ResourceGroup;
import org.apache.amoro.resource.ResourceType;
import org.apache.amoro.server.AmoroManagementConf;
import org.apache.amoro.server.DefaultOptimizingService;
import org.apache.amoro.server.manager.AbstractOptimizerContainer;
import org.apache.amoro.server.resource.Containers;
import org.apache.amoro.server.resource.OptimizerInstance;
import org.apache.amoro.server.resource.OptimizerManager;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// On AMS startup, removes every optimizer and optimizer group. A keeper then keeps the
// configured optimizer group and exactly one alive optimizer in it, creating them when missing.
// Note: later the logic can be changed once more than one optimizer per group is decided.
public class OptimizerBootstrap {
  private final Configurations serviceConfig;
  private final OptimizerManager optimizerManager;
  private final DefaultOptimizingService optimizingService;

  private ResourceGroup optimizerGroup;
  private int parallelism;

  private ScheduledExecutorService optimizerKeeper;

  public OptimizerBootstrap(
      Configurations serviceConfig,
      OptimizerManager optimizerManager,
      DefaultOptimizingService optimizingService) {
    this.serviceConfig = serviceConfig;
    this.optimizerManager = optimizerManager;
    this.optimizingService = optimizingService;
  }

  private static final Logger LOG = LoggerFactory.getLogger(OptimizerBootstrap.class);
  private static final long INTERVAL = TimeUnit.MINUTES.toMillis(5);

  public void run() {
    String optimizerGroupName =
        serviceConfig.getString(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_GROUP_NAME);
    String containerName =
        serviceConfig.getString(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_CONTAINER);
    if (StringUtils.isBlank(optimizerGroupName) || StringUtils.isBlank(containerName)) {
      LOG.info("Optimizer bootstrap is not configured (group-name/container missing), skipping.");
      return;
    }
    optimizerGroup = new ResourceGroup.Builder(optimizerGroupName, containerName).build();
    parallelism =
        serviceConfig
            .getOptional(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_PARALLELISM)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "optimizer.bootstrap.parallelism must be set when optimizer bootstrap is"
                            + " configured"));

    try {
      removeAllOptimizerGroups();
    } catch (Exception e) {
      LOG.error("Failed to remove optimizer groups, continuing with keeper", e);
    }

    optimizerKeeper =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "optimizer-bootstrap-keeper");
              thread.setDaemon(true);
              return thread;
            });
    optimizerKeeper.scheduleWithFixedDelay(
        this::keepOneOptimizerAlive, 0, INTERVAL, TimeUnit.MILLISECONDS);
    LOG.info("Optimizer keeper started for optimizer group {}", optimizerGroupName);
  }

  public void dispose() {
    if (optimizerKeeper != null) {
      optimizerKeeper.shutdownNow();
      optimizerKeeper = null;
    }
  }

  private void removeAllOptimizerGroups() {
    for (ResourceGroup existingOptimizerGroup : optimizerManager.listResourceGroups()) {
      String name = existingOptimizerGroup.getName();
      try {
        optimizerManager.listResourcesByGroup(name).forEach(this::releaseOptimizer);
        optimizerManager.deleteResourceGroup(name);
        optimizingService.deleteResourceGroup(name);
        LOG.info("Deleted optimizer group {}", name);
      } catch (Exception e) {
        LOG.warn("Failed to delete optimizer group {}", name, e);
      }
    }
  }

  private void keepOneOptimizerAlive() {
    try {
      // Startup removes all optimizer groups, so create the configured one before its optimizer.
      if (optimizerManager.getResourceGroup(optimizerGroup.getName()) == null) {
        LOG.info("Creating optimizer group {}", optimizerGroup.getName());
        optimizerManager.createResourceGroup(optimizerGroup);
        optimizingService.createResourceGroup(optimizerGroup);
      }

      Set<String> heartbeatingOptimizerIds =
          optimizerManager.listOptimizers(optimizerGroup.getName()).stream()
              .map(OptimizerInstance::getResourceId)
              .collect(Collectors.toSet());
      // Optimizers are listed newest first: keep the newest alive one and release the rest.
      Resource aliveOptimizer = null;
      for (Resource optimizer : optimizerManager.listResourcesByGroup(optimizerGroup.getName())) {
        if (aliveOptimizer == null
            && heartbeatingOptimizerIds.contains(optimizer.getResourceId())) {
          aliveOptimizer = optimizer;
        } else {
          LOG.warn("Releasing optimizer {}", optimizer.getResourceId());
          releaseOptimizer(optimizer);
        }
      }

      if (aliveOptimizer == null) {
        createOptimizer();
      }
    } catch (Exception e) {
      LOG.error("Optimizer keeper failed, will retry next tick", e);
    }
  }

  private void createOptimizer() {
    ResourceContainer container = Containers.get(optimizerGroup.getContainer());
    if (!(container instanceof AbstractOptimizerContainer)) {
      LOG.error("Container {} is not an optimizer container", optimizerGroup.getContainer());
      return;
    }
    Resource optimizer =
        new Resource.Builder(
                optimizerGroup.getContainer(), optimizerGroup.getName(), ResourceType.OPTIMIZER)
            .setProperties(optimizerGroup.getProperties())
            .setThreadCount(parallelism)
            .build();
    ((AbstractOptimizerContainer) container).requestResource(optimizer);
    optimizerManager.createResource(optimizer);
    LOG.info(
        "Started optimizer {} in optimizer group {} (parallelism={})",
        optimizer.getResourceId(),
        optimizerGroup.getName(),
        parallelism);
  }

  private void releaseOptimizer(Resource optimizer) {
    String optimizerId = optimizer.getResourceId();
    try {
      ResourceContainer container = Containers.get(optimizer.getContainerName());
      if (container instanceof AbstractOptimizerContainer) {
        ((AbstractOptimizerContainer) container).releaseResource(optimizer);
      }
    } catch (Exception e) {
      LOG.error("Failed to release optimizer {}", optimizerId, e);
    }
    try {
      optimizingService.deleteOptimizer(optimizer.getGroupName(), optimizerId);
      optimizerManager.deleteResource(optimizerId);
    } catch (Exception e) {
      LOG.error("Failed to clean up metadata of optimizer {}", optimizerId, e);
    }
  }
}
