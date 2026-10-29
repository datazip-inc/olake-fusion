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

import org.apache.amoro.api.OptimizingLogLine;
import org.apache.amoro.api.OptimizingTaskId;
import org.apache.amoro.api.OptimizingTaskResult;
import org.apache.amoro.client.OptimizingClientPools;
import org.apache.amoro.log.OptimizingTaskLogContext;
import org.apache.amoro.optimizer.common.OptimizerConfig;
import org.apache.amoro.optimizer.spark.OptimizingLogSender.LogLine;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.thrift.org.apache.thrift.TException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.apache.spark.SparkEnv;
import org.apache.spark.rpc.RpcAddress;
import org.apache.spark.rpc.RpcCallContext;
import org.apache.spark.rpc.RpcEndpointRef;
import org.apache.spark.rpc.RpcEnv;
import org.apache.spark.rpc.RpcTimeout;
import org.apache.spark.rpc.ThreadSafeRpcEndpoint;
import scala.PartialFunction;
import scala.concurrent.duration.FiniteDuration;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;
import scala.runtime.AbstractPartialFunction;
import scala.runtime.BoxedUnit;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Ships optimizing logs from the Spark optimizer to AMS, which is the only place they are stored.
 *
 * <p>A Log4j appender picks up the lines of compaction threads, the threads whose log context has a
 * {@code processId} (see {@link OptimizingTaskLogContext}). An executor sends its lines to the
 * driver over Spark RPC; the driver sends its own lines and the executors' lines to AMS through
 * {@code OptimizingService.appendLogs}.
 *
 * <p>No line is dropped: a batch leaves a buffer only once the next hop accepted it, and a task
 * whose lines were not delivered fails, so compaction stops rather than run without its logs. ERROR
 * lines are sent right away, so a crash right after them does not lose them.
 */
final class SparkOptimizingLogSupport {

  // How long a task waits at its end for its lines to be delivered before it fails.
  private static final long TASK_DELIVERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
  private static final long ERROR_DELIVERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(2);
  private static final String ENDPOINT_NAME = "amoro-optimizing-log";

  // Set on the driver only. Executors send to the driver through DriverSender.
  private static volatile AmsSender amsSender;

  private SparkOptimizingLogSupport() {}

  /** Starts log shipping on the driver. Throws if it cannot: compaction needs its logs. */
  static void registerOnDriver(OptimizerConfig config) {
    SparkEnv env = SparkEnv.get();
    Preconditions.checkState(env != null, "SparkEnv is not available");
    AmsSender sender = new AmsSender(config.getAmsUrl());
    env.rpcEnv().setupEndpoint(ENDPOINT_NAME, new LogEndpoint(env.rpcEnv(), sender));
    amsSender = sender;
  }

  /** Gives the driver's AMS sender the token the optimizer registered with. */
  static void onTokenChange(String token) {
    AmsSender sender = amsSender;
    if (sender != null) {
      sender.token = token;
    }
  }

  /**
   * Prepares log shipping before a task, on the driver or an executor. Throws if it cannot, so the
   * task does not run with its lines going nowhere.
   */
  static void beforeTask() {
    // Installs once per JVM, and again should Log4j have been reconfigured since.
    LogAppender.install();
    if (amsSender == null) {
      // An executor JVM. In Spark local mode tasks run in the driver JVM, which sends to AMS
      // itself.
      DriverSender.INSTANCE.connect();
    }
  }

  /**
   * Waits until this JVM's buffered lines reached the next hop: AMS from the driver, the driver
   * from an executor. A task whose lines did not arrive in time fails; one that failed already
   * keeps its own error.
   */
  static OptimizingTaskResult afterTask(OptimizingTaskResult result) {
    OptimizingLogSender sender = sender();
    if (sender.deliver(TASK_DELIVERY_TIMEOUT_MS) || result.getErrorMessage() != null) {
      return result;
    }
    OptimizingTaskResult failed =
        new OptimizingTaskResult(result.getTaskId(), result.getThreadId());
    failed.setErrorMessage("Optimizing logs could not be delivered to " + sender.target);
    return failed;
  }

  private static OptimizingLogSender sender() {
    AmsSender driverSender = amsSender;
    return driverSender != null ? driverSender : DriverSender.INSTANCE;
  }

  /** Hands the lines of compaction threads to this JVM's sender. */
  static final class LogAppender extends AbstractAppender {

    private static final String NAME = "OptimizingLogShipping";
    private static final String AMORO_LOGGER = "org.apache.amoro";

    // The line format AMS stores and LogController parses. A contract with AMS, so it is kept in
    // code rather than in log4j2.xml.
    private static final String JSON_LOG_PATTERN =
        "{\"level\":\"%p\",\"time\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}{UTC}\",\"processId\":\"%X{processId}\",\"taskId\":\"%X{taskId}\",\"logger\":\"%c{1}\",\"message\":\"%enc{%m}{JSON}\",\"stackTrace\":\"%enc{%throwable{full}}{JSON}\"}%n";

    private LogAppender(Layout<? extends Serializable> layout) {
      super(NAME, null, layout, true, Property.EMPTY_ARRAY);
    }

    /** Attaches the appender to the loggers Amoro code logs through, once per configuration. */
    static synchronized void install() {
      LoggerContext context = (LoggerContext) LogManager.getContext(false);
      Configuration config = context.getConfiguration();
      if (config.getAppender(NAME) != null) {
        return;
      }
      LogAppender appender =
          new LogAppender(
              PatternLayout.newBuilder()
                  .withPattern(JSON_LOG_PATTERN)
                  .withConfiguration(config)
                  .build());
      appender.start();
      config.addAppender(appender);
      // The logger config org.apache.amoro resolves to (the root one if the configuration has
      // none), plus its non-additive sub-loggers: each Amoro line then reaches the appender once.
      Set<LoggerConfig> targets = new LinkedHashSet<>();
      targets.add(config.getLoggerConfig(AMORO_LOGGER));
      for (LoggerConfig logger : config.getLoggers().values()) {
        if (logger.getName().startsWith(AMORO_LOGGER + ".") && !logger.isAdditive()) {
          targets.add(logger);
        }
      }
      targets.forEach(logger -> logger.addAppender(appender, null, null));
      context.updateLoggers();
    }

    @Override
    public void append(LogEvent event) {
      ReadOnlyStringMap logContext = event.getContextData();
      String processId = logContext.getValue(OptimizingTaskLogContext.PROCESS_ID_KEY);
      if (processId == null) {
        return; // not a compaction thread
      }
      String taskId = logContext.getValue(OptimizingTaskLogContext.TASK_ID_KEY);
      String ndjson =
          new String(getLayout().toByteArray(event), StandardCharsets.UTF_8).stripTrailing();
      OptimizingLogSender sender = sender();
      // Task id 0 is a driver line, which AMS writes to driver.log.
      sender.add(
          new LogLine(
              Long.parseLong(processId), taskId == null ? 0 : Integer.parseInt(taskId), ndjson));
      if (event.getLevel().isMoreSpecificThan(Level.ERROR) && !sender.isFailing()) {
        sender.deliver(ERROR_DELIVERY_TIMEOUT_MS);
      }
    }
  }

  /** Message an executor sends to the driver's {@link LogEndpoint}. */
  static final class LogBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    final List<LogLine> lines;

    LogBatch(List<LogLine> lines) {
      this.lines = lines;
    }
  }

  /** Driver endpoint: hands each executor batch to the AMS sender, then confirms it. */
  static final class LogEndpoint implements ThreadSafeRpcEndpoint {
    private final RpcEnv rpcEnv;
    private final AmsSender sender;

    LogEndpoint(RpcEnv rpcEnv, AmsSender sender) {
      this.rpcEnv = rpcEnv;
      this.sender = sender;
    }

    @Override
    public RpcEnv rpcEnv() {
      return rpcEnv;
    }

    @Override
    public PartialFunction<Object, BoxedUnit> receiveAndReply(RpcCallContext context) {
      return new AbstractPartialFunction<Object, BoxedUnit>() {
        @Override
        public boolean isDefinedAt(Object message) {
          return message instanceof LogBatch;
        }

        @Override
        public BoxedUnit apply(Object message) {
          ((LogBatch) message).lines.forEach(sender::add);
          context.reply(Boolean.TRUE);
          return BoxedUnit.UNIT;
        }
      };
    }
  }

  /** Driver: sends lines to AMS. */
  static final class AmsSender extends OptimizingLogSender {
    // Log calls get their own client pool (pools are cached per URL) with bounded waits, so an AMS
    // that stops answering does not hold up log delivery forever.
    private static final String CALL_TIMEOUTS = "socketTimeout=30000&connectTimeout=10000";

    private final String amsUrl;
    private volatile String token;

    AmsSender(String amsUrl) {
      super("AMS", TimeUnit.SECONDS.toMillis(1));
      this.amsUrl = amsUrl + (amsUrl.contains("?") ? "&" : "?") + CALL_TIMEOUTS;
    }

    @Override
    protected void send(List<LogLine> batch) throws TException {
      String currentToken = token;
      Preconditions.checkState(currentToken != null, "The optimizer is not registered with AMS");
      List<OptimizingLogLine> lines = new ArrayList<>(batch.size());
      for (LogLine line : batch) {
        lines.add(
            new OptimizingLogLine(new OptimizingTaskId(line.processId, line.taskId), line.ndjson));
      }
      OptimizingClientPools.getClient(amsUrl).appendLogs(currentToken, lines);
    }
  }

  /** Executor: sends lines to the driver's {@link LogEndpoint}. */
  static final class DriverSender extends OptimizingLogSender {
    // Created on first use, so only JVMs that send to the driver start its thread.
    static final DriverSender INSTANCE = new DriverSender();

    private static final ClassTag<Boolean> ACK = ClassTag$.MODULE$.apply(Boolean.class);

    private final RpcTimeout askTimeout =
        new RpcTimeout(
            FiniteDuration.apply(5, TimeUnit.SECONDS), "amoro.optimizing.log.askTimeout");
    private volatile RpcEndpointRef driver;

    private DriverSender() {
      super("the Spark driver", 200L);
    }

    /** Finds the driver's endpoint, once per executor JVM. */
    synchronized void connect() {
      if (driver != null) {
        return;
      }
      SparkEnv env = SparkEnv.get();
      Preconditions.checkState(env != null, "SparkEnv is not available");
      RpcAddress address =
          RpcAddress.apply(
              env.conf().get("spark.driver.host"),
              Integer.parseInt(env.conf().get("spark.driver.port")));
      driver = env.rpcEnv().setupEndpointRef(address, ENDPOINT_NAME);
    }

    @Override
    protected void send(List<LogLine> batch) {
      RpcEndpointRef endpoint = driver;
      Preconditions.checkState(endpoint != null, "Not connected to the Spark driver");
      endpoint.askSync(new LogBatch(batch), askTimeout, ACK);
    }
  }
}
