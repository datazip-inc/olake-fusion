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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Ensures: the configured OG and a single optimizer exist when AMS starts, then keeps a live
 * optimizer running via a periodic keeper that resubmits it if it dies or fails to start.
 */
public class OptimizerBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(OptimizerBootstrap.class);

  private static final long KEEP_ALIVE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
  // if the optimizer does not register within this time, it is considered failed and will be
  // resubmitted.
  private static final long STARTUP_GRACE_MS = TimeUnit.MINUTES.toMillis(4);

  private final Configurations serviceConfig;
  private final OptimizerManager optimizerManager;
  private final DefaultOptimizingService optimizingService;

  /** resourceId -> epoch millis when we submitted it, used as a startup grace window. */
  private final Map<String, Long> submittedAt = new ConcurrentHashMap<>();

  private String groupName;
  private AbstractOptimizerContainer optimizerContainer;
  private int parallelism;
  private int memoryMb;
  private ResourceGroup group;

  private ScheduledExecutorService optimizerKeeper;

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
      groupName = serviceConfig.getString(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_GROUP_NAME);
      String containerName =
          serviceConfig.getString(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_CONTAINER);

      if (StringUtils.isBlank(groupName) || StringUtils.isBlank(containerName)) {
        LOG.info("Optimizer bootstrap is not configured (group-name/container missing), skipping.");
        return;
      }

      ResourceContainer container = Containers.get(containerName);
      if (!(container instanceof AbstractOptimizerContainer)) {
        LOG.error("Container {} is not an optimizer container, skipping bootstrap", containerName);
        return;
      }
      optimizerContainer = (AbstractOptimizerContainer) container;
      parallelism = serviceConfig.getInteger(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_PARALLELISM);
      memoryMb = serviceConfig.getInteger(AmoroManagementConf.OPTIMIZER_BOOTSTRAP_MEMORY_MB);
      group = new ResourceGroup.Builder(groupName, containerName).build();

      ensureResourceGroup(group);
      releaseAllResourcesInGroup(groupName);
      Resource resource = createOptimizerResource(optimizerContainer, group, parallelism, memoryMb);
      submittedAt.put(resource.getResourceId(), System.currentTimeMillis());

      startOptimizerKeeper();
    } catch (Exception e) {
      LOG.error(
          "Optimizer bootstrap failed, AMS will continue without a bootstrapped optimizer", e);
    }
  }

  private void startOptimizerKeeper() {
    optimizerKeeper =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "optimizer-bootstrap-keeper");
              thread.setDaemon(true);
              return thread;
            });
    optimizerKeeper.scheduleWithFixedDelay(
        this::reconcile, KEEP_ALIVE_INTERVAL_MS, KEEP_ALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    LOG.info(
        "Optimizer bootstrap keeper started for group {} (interval {} ms)",
        groupName,
        KEEP_ALIVE_INTERVAL_MS);
  }

  /**
   * Ensures the bootstrapped group has a live optimizer: releases resources whose optimizer has
   * died (and whose startup grace has elapsed) and submits a replacement when none is healthy.
   */
  private void reconcile() {
    try {
      long now = System.currentTimeMillis();

      Set<String> liveResourceIds =
          optimizerManager.listOptimizers(groupName).stream()
              .map(OptimizerInstance::getResourceId)
              .filter(StringUtils::isNotBlank)
              .collect(Collectors.toSet());

      List<Resource> resources = optimizerManager.listResourcesByGroup(groupName);
      Set<String> knownResourceIds = new HashSet<>();
      boolean hasHealthyOptimizer = false;
      for (Resource resource : resources) {
        String resourceId = resource.getResourceId();
        if (liveResourceIds.contains(resourceId)) {
          submittedAt.remove(resourceId);
          hasHealthyOptimizer = true;
          knownResourceIds.add(resourceId);
        } else if (isWithinStartupGrace(resourceId, now)) {
          hasHealthyOptimizer = true;
          knownResourceIds.add(resourceId);
        } else {
          LOG.warn(
              "Optimizer resource {} in group {} has no live optimizer and its startup grace "
                  + "elapsed; releasing it for resubmission.",
              resourceId,
              groupName);
          releaseResource(resource);
          submittedAt.remove(resourceId);
        }
      }

      if (!hasHealthyOptimizer) {
        Resource resource =
            createOptimizerResource(optimizerContainer, group, parallelism, memoryMb);
        submittedAt.put(resource.getResourceId(), now);
        knownResourceIds.add(resource.getResourceId());
      }
      // Drop tracking for any resource no longer relevant (e.g. released elsewhere) to keep the
      // grace map bounded.
      submittedAt.keySet().retainAll(knownResourceIds);
    } catch (Exception e) {
      LOG.error("Optimizer bootstrap keeper reconcile failed, will retry next tick", e);
    }
  }

  private boolean isWithinStartupGrace(String resourceId, long now) {
    Long since = submittedAt.get(resourceId);
    return since != null && now - since < STARTUP_GRACE_MS;
  }

  public void dispose() {
    if (optimizerKeeper != null) {
      optimizerKeeper.shutdownNow();
      optimizerKeeper = null;
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

  private Resource createOptimizerResource(
      AbstractOptimizerContainer container, ResourceGroup group, int parallelism, int memoryMb) {
    Resource resource =
        new Resource.Builder(group.getContainer(), group.getName(), ResourceType.OPTIMIZER)
            .setProperties(group.getProperties())
            .setThreadCount(parallelism)
            .setMemoryMb(memoryMb)
            .build();
    container.requestResource(resource);
    optimizerManager.createResource(resource);
    LOG.info(
        "Started optimizer resource {} for group {} (parallelism={})",
        resource.getResourceId(),
        group.getName(),
        parallelism);
    return resource;
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
    try {
      ResourceContainer resourceContainer = Containers.get(resource.getContainerName());
      Preconditions.checkState(
          resourceContainer instanceof AbstractOptimizerContainer,
          "Cannot release optimizer on non-optimizer resource container %s.",
          resource.getContainerName());
      ((AbstractOptimizerContainer) resourceContainer).releaseResource(resource);
    } catch (Exception e) {
      LOG.warn(
          "Failed to release optimizer resource {}, cleaning up its metadata anyway",
          resourceId,
          e);
    }
    // Always clear the DB row and in-memory registration so the group is left with
    // exactly the single fresh optimizer created afterwards
    cleanupResource(resource.getGroupName(), resourceId);
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
  }
}
