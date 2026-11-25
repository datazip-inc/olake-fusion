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

package io.olake.fusion;

import io.olake.fusion.io.AuthenticatedFileIO;
import io.olake.fusion.table.TableIdentifier;
import io.olake.fusion.utils.CatalogUtil;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.iceberg.SerializableTable;
import org.apache.iceberg.Table;

import java.util.HashMap;
import java.util.Map;

/**
 * An Iceberg table loaded from a Fusion catalog. It is the Iceberg {@link Table} itself plus what
 * Fusion adds around it: the Fusion {@link TableIdentifier}, the catalog's {@code table.*} default
 * properties, and the catalog-authenticated {@link AuthenticatedFileIO}. All Iceberg reads and
 * commits go through {@link #table()} directly. This object stays in the server; it is never
 * serialized.
 */
public final class FusionTable {

  private final TableIdentifier identifier;
  private final Table table;
  private final AuthenticatedFileIO io;
  private final Map<String, String> catalogProperties;
  private Table serializableTable;
  private Map<String, String> serializableProperties;

  public FusionTable(
      TableIdentifier identifier,
      Table table,
      AuthenticatedFileIO io,
      Map<String, String> catalogProperties) {
    this.identifier = Preconditions.checkNotNull(identifier, "identifier");
    this.table = Preconditions.checkNotNull(table, "table");
    this.io = Preconditions.checkNotNull(io, "io");
    this.catalogProperties = catalogProperties;
  }

  /** Returns the {@link TableIdentifier} of this table */
  public TableIdentifier id() {
    return identifier;
  }

  /** Returns the name of this table */
  public String name() {
    return identifier.toString();
  }

  /** Returns the {@link TableFormat} of this table; Fusion only serves Iceberg tables. */
  public TableFormat format() {
    return TableFormat.ICEBERG;
  }

  /** Returns the Iceberg table. */
  public Table table() {
    return table;
  }

  /** Returns the file IO of the table, authenticated with the catalog's credentials. */
  public AuthenticatedFileIO io() {
    return io;
  }

  /**
   * Returns the table properties with the catalog's {@code table.*} defaults merged in; properties
   * set on the table win.
   */
  public Map<String, String> properties() {
    if (catalogProperties == null) {
      return table.properties();
    }
    return CatalogUtil.mergeCatalogPropertiesToTable(table.properties(), catalogProperties);
  }

  /**
   * Returns a read-only {@link SerializableTable} copy of the table for task inputs. It is made
   * once, so the inputs of one planned process share it and it is serialized once.
   */
  public synchronized Table serializableTable() {
    if (serializableTable == null) {
      serializableTable = SerializableTable.copyOf(table);
    }
    return serializableTable;
  }

  /** Returns {@link #properties()} as a map made once, for task inputs. */
  public synchronized Map<String, String> serializableProperties() {
    if (serializableProperties == null) {
      serializableProperties = new HashMap<>(properties());
    }
    return serializableProperties;
  }

  @Override
  public String toString() {
    return name();
  }
}
