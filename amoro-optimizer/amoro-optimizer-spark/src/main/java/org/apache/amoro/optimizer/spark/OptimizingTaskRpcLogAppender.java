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

package org.apache.amoro.optimizer.spark;

import org.apache.amoro.log.OptimizingLogLine;
import org.apache.amoro.log.OptimizingTaskLogContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Log4j2 appender that formats a log event as the existing NDJSON line and either accepts it into
 * the driver collector or sends it to the driver via Spark RPC.
 */
public class OptimizingTaskRpcLogAppender extends AbstractAppender {

  public static final String APPENDER_NAME = "OptimizingTaskRpc";

  private static final String ROUTING_APPENDER_KEY = "RoutingAppender";

  // Same pattern as docker/optimizer-spark/log4j2.xml JSON_LOG_PATTERN.
  static final String JSON_LOG_PATTERN =
      "{\"level\":\"%p\",\"time\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}{UTC}\",\"processId\":\"%X{processId}\",\"taskId\":\"%X{taskId}\",\"logger\":\"%c{1}\",\"message\":\"%enc{%m}{JSON}\",\"stackTrace\":\"%enc{%throwable{full}}{JSON}\"}%n";

  private static final Object INSTALL_LOCK = new Object();

  protected OptimizingTaskRpcLogAppender(
      String name, Filter filter, Layout<? extends Serializable> layout) {
    super(name, filter, layout, true, Property.EMPTY_ARRAY);
  }

  /**
   * Attach this appender to every logger that already uses {@code RoutingAppender}, once per JVM.
   */
  public static void install() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    Configuration config = context.getConfiguration();
    synchronized (INSTALL_LOCK) {
      if (config.getAppender(APPENDER_NAME) != null) {
        return;
      }
      PatternLayout layout =
          PatternLayout.newBuilder()
              .withPattern(JSON_LOG_PATTERN)
              .withConfiguration(config)
              .build();
      OptimizingTaskRpcLogAppender appender =
          new OptimizingTaskRpcLogAppender(APPENDER_NAME, null, layout);
      appender.start();
      config.addAppender(appender);
      addToLoggersWithRouting(config, appender);
      context.updateLoggers();
    }
  }

  private static void addToLoggersWithRouting(Configuration config, Appender appender) {
    if (config.getRootLogger().getAppenders().containsKey(ROUTING_APPENDER_KEY)) {
      config.getRootLogger().addAppender(appender, null, null);
    }
    for (Map.Entry<String, LoggerConfig> entry : config.getLoggers().entrySet()) {
      LoggerConfig loggerConfig = entry.getValue();
      if (loggerConfig.getAppenders().containsKey("RoutingAppender")
          && !loggerConfig.getAppenders().containsKey(APPENDER_NAME)) {
        loggerConfig.addAppender(appender, null, null);
      }
    }
  }

  @Override
  public void append(LogEvent event) {
    String channel = event.getContextData().getValue(OptimizingTaskLogContext.LOG_CHANNEL_KEY);
    if (channel == null) {
      return;
    }
    Layout<? extends Serializable> layout = getLayout();
    if (layout == null) {
      return;
    }
    String ndjson = new String(layout.toByteArray(event), StandardCharsets.UTF_8).stripTrailing();
    if (ndjson.isEmpty()) {
      return;
    }
    long processId =
        parseLong(event.getContextData().getValue(OptimizingTaskLogContext.PROCESS_ID_KEY));
    int taskId = parseInt(event.getContextData().getValue(OptimizingTaskLogContext.TASK_ID_KEY));
    String source =
        OptimizingTaskLogContext.LOG_CHANNEL_RPC.equals(channel)
            ? OptimizingLogLine.SOURCE_EXECUTOR
            : OptimizingLogLine.SOURCE_DRIVER;
    OptimizingLogLine line = new OptimizingLogLine(processId, taskId, ndjson, source);
    if (OptimizingLogCollector.isInitialized()) {
      OptimizingLogCollector.get().accept(line);
    } else {
      SparkOptimizingLogRpcClient.get().send(line);
    }
  }

  private static long parseLong(String value) {
    if (value == null || value.isEmpty()) {
      return 0L;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static int parseInt(String value) {
    if (value == null || value.isEmpty()) {
      // FILLER(need-your-change): driver lines have empty taskId in NDJSON; envelope uses 0.
      return 0;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
