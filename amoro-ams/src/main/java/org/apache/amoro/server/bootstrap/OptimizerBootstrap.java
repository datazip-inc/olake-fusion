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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.amoro.config.Configurations;
import org.apache.amoro.resource.Resource;
import org.apache.amoro.resource.ResourceContainer;
import org.apache.amoro.resource.ResourceGroup;
import org.apache.amoro.resource.ResourceType;
import org.apache.amoro.server.AmoroManagementConf;
import org.apache.amoro.server.DefaultOptimizingService;
import org.apache.amoro.server.manager.AbstractOptimizerContainer;
import org.apache.amoro.server.resource.ContainerMetadata;
import org.apache.amoro.server.resource.Containers;
import org.apache.amoro.server.resource.OptimizerInstance;
import org.apache.amoro.server.resource.OptimizerManager;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ensures the configured optimizer group and resource exist when AMS starts. */
public class OptimizerBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizerBootstrap.class);

  static final String CONTAINER_CONFIGS = "bootstrap.container-configs";

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

      int parallelism =
          serviceConfig.getInteger(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_PARALLELISM);

      ResourceContainer container = Containers.get(containerName);
      if (!(container instanceof AbstractOptimizerContainer)) {
        LOG.error("Container {} is not an optimizer container, skipping bootstrap", containerName);
        return;
      }
      AbstractOptimizerContainer optimizerContainer = (AbstractOptimizerContainer) container;

      ResourceGroup group =
          new ResourceGroup.Builder(groupName, containerName)
              .addProperties(readBootstrapProperties())
              .build();

      boolean groupChanged = ensureResourceGroup(group);
      List<Resource> resources = optimizerManager.listResourcesByGroup(groupName);
      String containerConfigs = buildContainerConfigs(containerName);
      long heartbeatTimeout =
          serviceConfig.getDurationInMillis(AmoroManagementConf.OPTIMIZER_HB_TIMEOUT);
      // on what conditions we need resource recreate
      if (!needsResourceRecreate(
          groupChanged, groupName, parallelism, containerConfigs, heartbeatTimeout, resources)) {
        LOG.info("Optimizer for group {} already matches config", groupName);
        return;
      }

      LOG.info(
          "Recreating optimizer for group {} (resources={}, parallelism={})",
          groupName,
          resources.size(),
          parallelism);

      for (Resource resource : resources) {
        releaseResource(resource);
      }

      createOptimizerResource(optimizerContainer, group, parallelism, containerConfigs);
    } catch (Exception e) {
      LOG.error(
          "Optimizer bootstrap failed, AMS will continue without a bootstrapped optimizer", e);
    }
  }

  /** @return true if the group was created or updated */
  private boolean ensureResourceGroup(ResourceGroup group) {
    ResourceGroup existing = optimizerManager.getResourceGroup(group.getName());
    if (existing == null) {
      LOG.info(
          "Creating optimizer group {} on container {}", group.getName(), group.getContainer());
      optimizerManager.createResourceGroup(group);
      optimizingService.createResourceGroup(group);
      return true;
    }
    if (resourceGroupMatches(existing, group)) {
      return false;
    }
    LOG.info("Updating optimizer group {} to match config", group.getName());
    optimizerManager.updateResourceGroup(group);
    optimizingService.updateResourceGroup(group);
    return true;
  }

  boolean needsResourceRecreate(
      boolean groupChanged,
      String groupName,
      int parallelism,
      String containerConfigs,
      long heartbeatTimeout,
      List<Resource> resources) {
    if (groupChanged || resources.size() != 1) {
      return true;
    }

    Resource resource = resources.get(0);
    if (resource.getThreadCount() != parallelism) {
      return true;
    }

    Map<String, String> properties = resource.getProperties();
    String storedContainerConfigs = properties == null ? null : properties.get(CONTAINER_CONFIGS);
    if (!Objects.equals(storedContainerConfigs, containerConfigs)) {
      LOG.info("Container config changed for group {}, recreating optimizer", groupName);
      return true;
    }

    if (!isOptimizerAlive(groupName, resource.getResourceId(), heartbeatTimeout)) {
      LOG.info(
          "No live optimizer for resource {} in group {}, recreating",
          resource.getResourceId(),
          groupName);
      return true;
    }

    return false;
  }

  private boolean isOptimizerAlive(String groupName, String resourceId, long heartbeatTimeout) {
    long now = System.currentTimeMillis();
    return optimizerManager.listOptimizers(groupName).stream()
        .filter(instance -> resourceId.equals(instance.getResourceId()))
        .anyMatch(instance -> instance.getTouchTime() + heartbeatTimeout > now);
  }

  private void createOptimizerResource(
      AbstractOptimizerContainer container,
      ResourceGroup group,
      int parallelism,
      String containerConfigs) {
    Map<String, String> properties = new HashMap<>();
    if (group.getProperties() != null) {
      properties.putAll(group.getProperties());
    }
    properties.put(CONTAINER_CONFIGS, containerConfigs);

    Resource resource =
        new Resource.Builder(group.getContainer(), group.getName(), ResourceType.OPTIMIZER)
            .setProperties(properties)
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

  private String buildContainerConfigs(String containerName) {
    Map<String, String> properties =
        Containers.getMetadataList().stream()
            .filter(metadata -> containerName.equals(metadata.getName()))
            .findFirst()
            .map(ContainerMetadata::getProperties)
            .orElseGet(HashMap::new);

    return properties.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining("|"));
  }

  private Map<String, String> readBootstrapProperties() {
    String prefix = AmoroManagementConf.OPTIMIZER_BOOTSTRAP_PROPERTIES_PREFIX;
    Map<String, String> properties = new HashMap<>();
    serviceConfig
        .toMap()
        .forEach(
            (key, value) -> {
              if (key.startsWith(prefix)) {
                properties.put(key.substring(prefix.length()), value);
              }
            });
    return properties;
  }

  private boolean resourceGroupMatches(ResourceGroup existing, ResourceGroup desired) {
    Map<String, String> existingProperties =
        existing.getProperties() == null ? new HashMap<>() : existing.getProperties();
    Map<String, String> desiredProperties =
        desired.getProperties() == null ? new HashMap<>() : desired.getProperties();
    return Objects.equals(existing.getContainer(), desired.getContainer())
        && existingProperties.equals(desiredProperties);
  }

  private void releaseResource(Resource resource) {
    String resourceId = resource.getResourceId();
    try {
      List<OptimizerInstance> instances =
          optimizerManager.listOptimizers(resource.getGroupName()).stream()
              .filter(instance -> resourceId.equals(instance.getResourceId()))
              .collect(Collectors.toList());
      if (!instances.isEmpty()) {
        resource.getProperties().putAll(instances.get(0).getProperties());
      }

      ResourceContainer resourceContainer = Containers.get(resource.getContainerName());
      Preconditions.checkState(
          resourceContainer instanceof AbstractOptimizerContainer,
          "Cannot release optimizer on non-optimizer resource container %s.",
          resource.getContainerName());
      ((AbstractOptimizerContainer) resourceContainer).releaseResource(resource);
    } catch (Exception e) {
      LOG.warn("Failed to release optimizer resource {}, cleaning up metadata", resourceId, e);
    } finally {
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
      LOG.debug("Optimizer {} was not registered in optimizing service", resourceId);
    }
    try {
      optimizerManager.deleteResource(resourceId);
    } catch (Exception e) {
      LOG.warn("Failed to delete resource {} from database", resourceId, e);
    }
    try {
      optimizerManager.deleteOptimizer(groupName, resourceId);
    } catch (Exception e) {
      LOG.debug("Optimizer {} was not found in database", resourceId);
    }
  }
}
