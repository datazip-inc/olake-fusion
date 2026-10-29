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

package io.olake.fusion.server.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import io.olake.fusion.FusionTable;
import io.olake.fusion.TableFormat;
import io.olake.fusion.api.CatalogMeta;
import io.olake.fusion.config.Configurations;
import io.olake.fusion.exception.ObjectNotExistsException;
import io.olake.fusion.formats.iceberg.IcebergTable;
import io.olake.fusion.server.FusionManagementConf;
import io.olake.fusion.server.RestCatalogService;
import io.olake.fusion.server.table.TableMetadata;
import io.olake.fusion.server.table.internal.InternalIcebergCreator;
import io.olake.fusion.server.table.internal.InternalIcebergHandler;
import io.olake.fusion.server.table.internal.InternalTableCreator;
import io.olake.fusion.server.table.internal.InternalTableHandler;
import io.olake.fusion.utils.CatalogUtil;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.requests.CreateTableRequest;

public class InternalCatalogImpl extends InternalCatalog {

  private static final String URI = "uri";

  final int httpPort;
  final String exposedHost;

  final Cache<FusionTable<?>, FileIO> fileIOCloser;

  protected InternalCatalogImpl(CatalogMeta metadata, Configurations serverConfiguration) {
    super(metadata);
    this.httpPort = serverConfiguration.getInteger(FusionManagementConf.HTTP_SERVER_PORT);
    this.exposedHost = serverConfiguration.getString(FusionManagementConf.SERVER_EXPOSE_HOST);
    this.fileIOCloser = newFileIOCloser();
  }

  @Override
  public CatalogMeta getMetadata() {
    CatalogMeta meta = super.getMetadata();
    if (!meta.getCatalogProperties().containsKey(URI)) {
      meta.putToCatalogProperties(URI, defaultRestURI());
    }
    meta.putToCatalogProperties(CatalogProperties.CATALOG_IMPL, RESTCatalog.class.getName());
    return meta.deepCopy();
  }

  @Override
  public void updateMetadata(CatalogMeta metadata) {
    String defaultUrl = defaultRestURI();
    String uri = metadata.getCatalogProperties().getOrDefault(URI, defaultUrl);
    if (defaultUrl.equals(uri)) {
      metadata.getCatalogProperties().remove(URI);
    }
    super.updateMetadata(metadata);
  }

  @Override
  public FusionTable<?> loadTable(String database, String tableName) {
    InternalTableHandler<TableOperations> handler;
    try {
      handler = newTableHandler(database, tableName);
    } catch (ObjectNotExistsException e) {
      return null;
    }
    if (TableFormat.ICEBERG.equals(handler.tableMetadata().getFormat())) {
      return loadIcebergTable(database, tableName, handler);
    } else {
      throw new IllegalArgumentException(
          "Unsupported table format:" + handler.tableMetadata().getFormat());
    }
  }

  private FusionTable<?> loadIcebergTable(
      String database, String tableName, InternalTableHandler<TableOperations> handler) {
    TableMetadata tableMetadata = handler.tableMetadata();
    TableOperations ops = handler.newTableOperator();

    BaseTable table =
        new BaseTable(
            ops,
            TableIdentifier.of(
                    tableMetadata.getTableIdentifier().getDatabase(),
                    tableMetadata.getTableIdentifier().getTableName())
                .toString());
    io.olake.fusion.table.TableIdentifier tableIdentifier =
        io.olake.fusion.table.TableIdentifier.of(name(), database, tableName);
    FusionTable<?> amoroTable =
        IcebergTable.newIcebergTable(
            tableIdentifier,
            table,
            CatalogUtil.buildMetaStore(getMetadata()),
            getMetadata().getCatalogProperties());
    fileIOCloser.put(amoroTable, ops.io());
    return amoroTable;
  }

  private String defaultRestURI() {
    return "http://" + exposedHost + ":" + httpPort + RestCatalogService.ICEBERG_REST_API_PREFIX;
  }

  @Override
  public InternalTableCreator newTableCreator(
      String database, String tableName, TableFormat format, CreateTableRequest creatorArguments) {
    if (tableExists(database, tableName)) {
      throw new AlreadyExistsException(
          "Table " + name() + "." + database + "." + tableName + " already exists.");
    }
    if (TableFormat.ICEBERG.equals(format)) {
      return new InternalIcebergCreator(getMetadata(), database, tableName, creatorArguments);
    } else {
      throw new IllegalArgumentException("Unsupported table format:" + format);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public InternalTableHandler<TableOperations> newTableHandler(String database, String tableName) {
    TableMetadata metadata = loadTableMetadata(database, tableName);
    if (TableFormat.ICEBERG.equals(metadata.getFormat())) {
      return new InternalIcebergHandler(getMetadata(), metadata);
    } else {
      throw new IllegalArgumentException("Unsupported table format:" + metadata.getFormat());
    }
  }

  private Cache<FusionTable<?>, FileIO> newFileIOCloser() {
    return Caffeine.newBuilder()
        .weakKeys()
        .removalListener(
            (RemovalListener<FusionTable<?>, FileIO>)
                (tbl, fileIO, cause) -> {
                  if (null != fileIO) {
                    fileIO.close();
                  }
                })
        .build();
  }
}
