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

package io.olake.fusion.client;

import org.apache.amoro.shade.guava32.com.google.common.base.MoreObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public class AmsThriftUrl {
  public static final String PARAM_SOCKET_TIMEOUT = "socketTimeout";
  public static final int DEFAULT_SOCKET_TIMEOUT = 5000;
  public static final String THRIFT_URL_FORMAT = "thrift://%s:%d/%s%s";
  private static final Logger logger = LoggerFactory.getLogger(AmsThriftUrl.class);
  private final String schema;
  private final String host;
  private final int port;
  private final String catalogName;
  private final int socketTimeout;

  // origin url before parse
  private final String url;

  private AmsThriftUrl(
      String schema, String host, int port, String catalogName, int socketTimeout, String url) {
    this.schema = schema;
    this.host = host;
    this.port = port;
    this.catalogName = catalogName;
    this.socketTimeout = socketTimeout;
    this.url = url;
  }

  /**
   * parse thrift url, now support thrift://host:port/{catalogName} and
   * zookeeper://host:port/{cluster}/{catalogName} . parse to AmsThriftUrl that contains properties
   * schema, host, port, catalogName, socketTimeout, url.
   *
   * @param url - thrift url
   * @return -
   */
  public static AmsThriftUrl parse(String url, String serviceName) {
    if (url == null) {
      throw new IllegalArgumentException("thrift url is null");
    }
    return parserThriftUrl(url);
  }

  private static AmsThriftUrl parserThriftUrl(String url) {
    int socketTimeout = DEFAULT_SOCKET_TIMEOUT;
    try {
      URI uri = new URI(url.toLowerCase(Locale.ROOT));
      String schema = uri.getScheme();
      String host = uri.getHost();
      int port = uri.getPort();
      String path = uri.getPath();
      if (path != null && path.startsWith("/")) {
        path = path.substring(1);
      }
      if (uri.getQuery() != null) {
        for (String paramExpression : uri.getQuery().split("&")) {
          String[] paramSplit = paramExpression.split("=");
          if (paramSplit.length == 2) {
            if (paramSplit[0].equalsIgnoreCase(PARAM_SOCKET_TIMEOUT)) {
              socketTimeout = Integer.parseInt(paramSplit[1]);
            }
          }
        }
      }
      String catalogName = path;
      return new AmsThriftUrl(schema, host, port, catalogName, socketTimeout, url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("parse metastore url failed", e);
    }
  }

  public String schema() {
    return schema;
  }

  public String host() {
    return host;
  }

  public int port() {
    return port;
  }

  public String catalogName() {
    return catalogName;
  }

  public int socketTimeout() {
    return socketTimeout;
  }

  public String url() {
    return url;
  }

  public String serverUrl() {
    return String.format(THRIFT_URL_FORMAT, host, port, "", "");
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("schema", schema)
        .add("host", host)
        .add("port", port)
        .add("catalogName", catalogName)
        .add("socketTimeout", socketTimeout)
        .add("url", url)
        .toString();
  }
}
