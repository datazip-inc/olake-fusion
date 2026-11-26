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

package org.apache.thrift.transport;

/**
 * Compatibility shim: Thrift 0.9.3's framed transport, at the name Hive metastore 3.1.3 still
 * loads.
 *
 * <p>Why it exists: Fusion pins {@code libthrift} 0.24.0 for security fixes (CVE-2026-43871 and
 * older Thrift CVEs). Thrift 0.14 moved this class to {@code org.apache.thrift.transport.layered},
 * but {@code hive-standalone-metastore} 3.1.3, the client Iceberg's {@code HiveCatalog} uses for
 * Hive catalogs, was compiled against Thrift 0.9.3 and still references the old name. Without this
 * class, loading Hive's metastore classes fails with {@code NoClassDefFoundError:
 * org/apache/thrift/transport/TFramedTransport}.
 *
 * <p>What it is: a subclass of the moved class with the constructors and {@code Factory} Hive
 * calls; all behaviour comes from Thrift itself. It is the only class with this name on the
 * classpath, as Thrift 0.24 no longer has one.
 *
 * <p>When to remove it, together with the {@code TFilterTransport} shim in {@code
 * org.apache.hadoop.hive.metastore.security}:
 *
 * <ul>
 *   <li>the Hive metastore client is upgraded to a version built against Thrift 0.14 or newer (for
 *       example Hive 4.x, built against Thrift 0.16), or
 *   <li>Fusion stops supporting Hive catalogs, or
 *   <li>{@code libthrift.version} goes back below 0.14 (this class would no longer compile).
 * </ul>
 *
 * <p>To confirm it is no longer needed, delete both shims and run {@code
 * TestIcebergOnlyCatalogService} in fusion-ams, which starts a real Hive metastore and connects to
 * it through {@code HiveCatalog}.
 */
public class TFramedTransport extends org.apache.thrift.transport.layered.TFramedTransport {

  public TFramedTransport(TTransport transport, int maxLength) throws TTransportException {
    super(transport, maxLength);
  }

  public TFramedTransport(TTransport transport) throws TTransportException {
    super(transport);
  }

  public static class Factory extends org.apache.thrift.transport.layered.TFramedTransport.Factory {

    public Factory() {
      super();
    }

    public Factory(int maxLength) {
      super(maxLength);
    }
  }
}
