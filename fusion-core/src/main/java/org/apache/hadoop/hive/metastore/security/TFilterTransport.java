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

package org.apache.hadoop.hive.metastore.security;

import org.apache.thrift.TConfiguration;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * Transport that simply wraps another transport. This is the equivalent of FilterInputStream for
 * Thrift transports.
 *
 * <p>Compatibility shim. Why it exists: Fusion pins {@code libthrift} 0.24.0 for security fixes
 * (CVE-2026-43871 and older Thrift CVEs). Thrift 0.14 added three abstract methods to {@link
 * TTransport} ({@code getConfiguration}, {@code updateKnownMessageSize}, {@code
 * checkReadBytesAvailable}). Hive metastore 3.1.3's own {@code TFilterTransport} does not implement
 * them, so on a current Thrift the transports built on it ({@code TUGIContainingTransport} on a
 * metastore server, {@code TUGIAssumingTransport} on a Kerberos/SASL client) fail with {@code
 * AbstractMethodError}.
 *
 * <p>What it is: Hive 3.1.3's source of this class, taken unchanged from {@code
 * hive-standalone-metastore-3.1.3-sources.jar}, plus the three methods, which forward to the
 * wrapped transport like every other method here.
 *
 * <p>How it takes effect: Hive's original class is still on the classpath; the JVM loads whichever
 * comes first, and this one does. The server's {@code bin/fusion.sh} sorts {@code lib/*.jar}, so
 * {@code fusion-core-*.jar} comes before {@code hive-standalone-metastore-*.jar}, and Maven puts
 * fusion-core's classes first too. Putting a Hive jar ahead of fusion-core (for example through
 * {@code FUSION_ADDITION_CLASSPATH}) would bring the failure back.
 *
 * <p>When to remove it, together with the {@code org.apache.thrift.transport.TFramedTransport}
 * shim:
 *
 * <ul>
 *   <li>the Hive metastore client is upgraded to a version built against Thrift 0.14 or newer (for
 *       example Hive 4.x, built against Thrift 0.16), whose own class implements these methods, or
 *   <li>Fusion stops supporting Hive catalogs, or
 *   <li>{@code libthrift.version} goes back below 0.14.
 * </ul>
 *
 * <p>To confirm it is no longer needed, delete both shims and run {@code
 * TestIcebergOnlyCatalogService} in fusion-ams, which starts a real Hive metastore and connects to
 * it through {@code HiveCatalog}.
 */
public class TFilterTransport extends TTransport {
  protected final TTransport wrapped;

  public TFilterTransport(TTransport wrapped) {
    this.wrapped = wrapped;
  }

  @Override
  public void open() throws TTransportException {
    wrapped.open();
  }

  @Override
  public boolean isOpen() {
    return wrapped.isOpen();
  }

  @Override
  public boolean peek() {
    return wrapped.peek();
  }

  @Override
  public void close() {
    wrapped.close();
  }

  @Override
  public int read(byte[] buf, int off, int len) throws TTransportException {
    return wrapped.read(buf, off, len);
  }

  @Override
  public int readAll(byte[] buf, int off, int len) throws TTransportException {
    return wrapped.readAll(buf, off, len);
  }

  @Override
  public void write(byte[] buf) throws TTransportException {
    wrapped.write(buf);
  }

  @Override
  public void write(byte[] buf, int off, int len) throws TTransportException {
    wrapped.write(buf, off, len);
  }

  @Override
  public void flush() throws TTransportException {
    wrapped.flush();
  }

  @Override
  public byte[] getBuffer() {
    return wrapped.getBuffer();
  }

  @Override
  public int getBufferPosition() {
    return wrapped.getBufferPosition();
  }

  @Override
  public int getBytesRemainingInBuffer() {
    return wrapped.getBytesRemainingInBuffer();
  }

  @Override
  public void consumeBuffer(int len) {
    wrapped.consumeBuffer(len);
  }

  @Override
  public TConfiguration getConfiguration() {
    return wrapped.getConfiguration();
  }

  @Override
  public void updateKnownMessageSize(long size) throws TTransportException {
    wrapped.updateKnownMessageSize(size);
  }

  @Override
  public void checkReadBytesAvailable(long numBytes) throws TTransportException {
    wrapped.checkReadBytesAvailable(numBytes);
  }
}
