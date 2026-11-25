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

package io.olake.fusion.server.resource;

import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.properties.CatalogMetaProperties;
import io.olake.fusion.resource.Resource;
import io.olake.fusion.resource.ResourceGroup;
import io.olake.fusion.server.catalog.CatalogManager;
import io.olake.fusion.server.persistence.PersistentBase;
import io.olake.fusion.server.persistence.TableRuntimeMeta;
import io.olake.fusion.server.persistence.mapper.OptimizerMapper;
import io.olake.fusion.server.persistence.mapper.ResourceMapper;
import io.olake.fusion.server.persistence.mapper.TableRuntimeMapper;
import io.olake.fusion.table.TableProperties;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;

import java.util.List;
import java.util.stream.Collectors;

/**
 * DefaultOptimizingService is implementing the OptimizerManager Thrift service, which manages the
 * optimizing tasks for tables. It includes methods for authenticating optimizers, polling tasks
 * from the optimizing queue, acknowledging tasks,and completing tasks. The code uses several data
 * structures, including maps for optimizing queues ,task runtimes, and authenticated optimizers.
 *
 * <p>The code also includes a TimerTask for detecting and removing expired optimizers and
 * suspending tasks.
 */
public class DefaultOptimizerManager extends PersistentBase implements OptimizerManager {

  protected final Configurations serverConfiguration;
  private final CatalogManager catalogManager;

  public DefaultOptimizerManager(
      Configurations serverConfiguration, CatalogManager catalogManager) {
    this.catalogManager = catalogManager;
    this.serverConfiguration = serverConfiguration;
  }

  private void unregisterOptimizer(String token) {
    doAs(OptimizerMapper.class, mapper -> mapper.deleteOptimizer(token));
  }

  @Override
  public List<OptimizerInstance> listOptimizers() {
    return getAs(OptimizerMapper.class, OptimizerMapper::selectAll);
  }

  @Override
  public List<OptimizerInstance> listOptimizers(String group) {
    List<OptimizerInstance> authOptimizers =
        getAs(OptimizerMapper.class, OptimizerMapper::selectAll);
    return authOptimizers.stream()
        .filter(optimizer -> optimizer.getGroupName().equals(group))
        .collect(Collectors.toList());
  }

  @Override
  public void deleteOptimizer(String group, String resourceId) {
    List<OptimizerInstance> deleteOptimizers =
        getAs(OptimizerMapper.class, mapper -> mapper.selectByResourceId(resourceId));
    deleteOptimizers.forEach(
        optimizer -> {
          String token = optimizer.getToken();
          unregisterOptimizer(token);
        });
  }

  @Override
  public void createResourceGroup(ResourceGroup resourceGroup) {
    doAs(ResourceMapper.class, mapper -> mapper.insertResourceGroup(resourceGroup));
  }

  @Override
  public void deleteResourceGroup(String groupName) {
    if (canDeleteResourceGroup(groupName)) {
      doAs(ResourceMapper.class, mapper -> mapper.deleteResourceGroup(groupName));
    } else {
      throw new RuntimeException(
          String.format(
              "The resource group %s cannot be deleted because it is currently in use.",
              groupName));
    }
  }

  @Override
  public void updateResourceGroup(ResourceGroup resourceGroup) {
    Preconditions.checkNotNull(resourceGroup, "The resource group cannot be null.");
    doAs(ResourceMapper.class, mapper -> mapper.updateResourceGroup(resourceGroup));
  }

  @Override
  public void createResource(Resource resource) {
    doAs(ResourceMapper.class, mapper -> mapper.insertResource(resource));
  }

  @Override
  public void deleteResource(String resourceId) {
    doAs(ResourceMapper.class, mapper -> mapper.deleteResource(resourceId));
  }

  @Override
  public List<ResourceGroup> listResourceGroups() {
    return getAs(ResourceMapper.class, ResourceMapper::selectResourceGroups);
  }

  @Override
  public List<ResourceGroup> listResourceGroups(String containerName) {
    return getAs(ResourceMapper.class, ResourceMapper::selectResourceGroups).stream()
        .filter(group -> group.getContainer().equals(containerName))
        .collect(Collectors.toList());
  }

  @Override
  public ResourceGroup getResourceGroup(String groupName) {
    return getAs(ResourceMapper.class, mapper -> mapper.selectResourceGroup(groupName));
  }

  @Override
  public List<Resource> listResourcesByGroup(String groupName) {
    return getAs(ResourceMapper.class, mapper -> mapper.selectResourcesByGroup(groupName));
  }

  @Override
  public Resource getResource(String resourceId) {
    return getAs(ResourceMapper.class, mapper -> mapper.selectResource(resourceId));
  }

  @Override
  public boolean canDeleteResourceGroup(String name) {
    for (CatalogMeta catalogMeta : catalogManager.listCatalogMetas()) {
      if (catalogMeta.getCatalogProperties() != null
          && catalogMeta
              .getCatalogProperties()
              .getOrDefault(
                  CatalogMetaProperties.TABLE_PROPERTIES_PREFIX
                      + TableProperties.SELF_OPTIMIZING_GROUP,
                  TableProperties.SELF_OPTIMIZING_GROUP_DEFAULT)
              .equals(name)) {
        return false;
      }
    }
    for (OptimizerInstance optimizer : listOptimizers()) {
      if (optimizer.getGroupName().equals(name)) {
        return false;
      }
    }
    List<TableRuntimeMeta> tableRuntimeMetas =
        getAs(TableRuntimeMapper.class, mapper -> mapper.queryForGroups(name, null, null, null));
    return tableRuntimeMetas.isEmpty();
  }
}
