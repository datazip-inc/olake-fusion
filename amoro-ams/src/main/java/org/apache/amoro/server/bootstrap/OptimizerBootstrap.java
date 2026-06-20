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
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Ensures the configured optimizer group and resource exist when AMS starts. */
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
    try {
      String groupName =
          serviceConfig.getString(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_GROUP_NAME);
      String containerName =
          serviceConfig.getString(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_CONTAINER);

      ResourceContainer container = Containers.get(containerName);
      if (!(container instanceof AbstractOptimizerContainer)) {
        LOG.error("Container {} is not an optimizer container, skipping bootstrap", containerName);
        return;
      }
      AbstractOptimizerContainer optimizerContainer = (AbstractOptimizerContainer) container;
      int parallelism =
          serviceConfig.getInteger(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_PARALLELISM);
      ResourceGroup group = new ResourceGroup.Builder(groupName, containerName).build();
      ensureResourceGroup(group);
      releaseAllResourcesInGroup(groupName);
      createOptimizerResource(optimizerContainer, group, parallelism);
    } catch (Exception e) {
      LOG.error(
          "Optimizer bootstrap failed, AMS will continue without a bootstrapped optimizer", e);
    }
  }

  private void ensureResourceGroup(ResourceGroup group) {
    ResourceGroup current = optimizerManager.getResourceGroup(group.getName());
    if (current != null && resourceGroupMatches(current, group)) {
      return;
    }
    if (current == null) {
      LOG.info(
          "Creating optimizer group {} on container {}", group.getName(), group.getContainer());
      optimizerManager.createResourceGroup(group);
      optimizingService.createResourceGroup(group);
    } else {
      LOG.info("Updating optimizer group {} to match config", group.getName());
      optimizerManager.updateResourceGroup(group);
      optimizingService.updateResourceGroup(group);
    }
  }

  private void createOptimizerResource(
      AbstractOptimizerContainer container, ResourceGroup group, int parallelism) {
    Resource resource =
        new Resource.Builder(group.getContainer(), group.getName(), ResourceType.OPTIMIZER)
            .setProperties(group.getProperties())
            .setThreadCount(parallelism)
            .build();
    container.requestResource(resource);
    optimizerManager.createResource(resource);
    LOG.info(
        "Started optimizer resource {} for group {} (parallelism={})",
        resource.getResourceId(),
        group.getName(),
        parallelism);
  }

  private boolean resourceGroupMatches(ResourceGroup current, ResourceGroup newGroup) {
    Map<String, String> currentProperties =
        current.getProperties() == null ? new HashMap<>() : current.getProperties();
    Map<String, String> newProperties =
        newGroup.getProperties() == null ? new HashMap<>() : newGroup.getProperties();
    return Objects.equals(current.getContainer(), newGroup.getContainer())
        && currentProperties.equals(newProperties);
  }

  private void releaseAllResourcesInGroup(String groupName) {
    List<Resource> resources = optimizerManager.listResourcesByGroup(groupName);
    for (Resource resource : resources) {
      releaseResource(resource);
    }
  }

  private void releaseResource(Resource resource) {
    String resourceId = resource.getResourceId();
    boolean released = false;
    try {
      List<OptimizerInstance> instances =
          optimizerManager.listOptimizers(resource.getGroupName()).stream()
              .filter(instance -> resourceId.equals(instance.getResourceId()))
              .collect(Collectors.toList());
      Map<String, String> mergedProperties = new HashMap<>();
      if (resource.getProperties() != null) {
        mergedProperties.putAll(resource.getProperties());
      }
      if (!instances.isEmpty() && instances.get(0).getProperties() != null) {
        mergedProperties.putAll(instances.get(0).getProperties());
      }
      resource.setProperties(mergedProperties);

      ResourceContainer resourceContainer = Containers.get(resource.getContainerName());
      Preconditions.checkState(
          resourceContainer instanceof AbstractOptimizerContainer,
          "Cannot release optimizer on non-optimizer resource container %s.",
          resource.getContainerName());
      ((AbstractOptimizerContainer) resourceContainer).releaseResource(resource);
      released = true;
    } catch (Exception e) {
      LOG.warn("Failed to release optimizer resource {}", resourceId, e);
    }
    if (released) {
      cleanupResource(resource.getGroupName(), resourceId);
    }
  }

  private void cleanupResource(String groupName, String resourceId) {
    if (StringUtils.isBlank(resourceId)) {
      return;
    }
    try {
      optimizingService.deleteOptimizer(groupName, resourceId);
    } catch (Exception e) {
      LOG.warn(
          "Failed to delete optimizer {} from optimizing service during cleanup", resourceId, e);
    }
    try {
      optimizerManager.deleteResource(resourceId);
    } catch (Exception e) {
      LOG.warn("Failed to delete resource {} from database", resourceId, e);
    }
    try {
      optimizerManager.deleteOptimizer(groupName, resourceId);
    } catch (Exception e) {
      LOG.warn("Failed to delete optimizer {} from database during cleanup", resourceId, e);
    }
  }
}
