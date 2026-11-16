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
import org.apache.amoro.client.OptimizingClientPools;
import org.apache.amoro.log.OptimizingTaskLogContext;
import org.apache.amoro.shade.thrift.org.apache.thrift.TApplicationException;
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
import org.apache.spark.SparkConf;
import org.apache.spark.SparkEnv;
import org.apache.spark.rpc.RpcAddress;
import org.apache.spark.rpc.RpcCallContext;
import org.apache.spark.rpc.RpcEndpointRef;
import org.apache.spark.rpc.RpcEnv;
import org.apache.spark.rpc.RpcTimeout;
import org.apache.spark.rpc.ThreadSafeRpcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Ships optimizing logs from the Spark optimizer to AMS, which stores them.
 *
 * <p>An appender picks up the lines of compaction threads, the threads whose log context has a
 * {@code processId} (see {@link OptimizingTaskLogContext}). An executor sends its lines to the
 * driver over Spark RPC; the driver sends its own lines and the executors' to AMS through {@code
 * OptimizingService.appendLogs}.
 *
 * <p>Log shipping never decides a task's outcome. At the end of a task the task waits a short,
 * bounded time for its lines to reach the next hop, and then returns its result as it is; lines not
 * yet delivered keep being sent in the background. A failure to set shipping up is logged and the
 * task runs without it.
 */
final class SparkLogShipping {

  private static final Logger LOG = LoggerFactory.getLogger(SparkLogShipping.class);

  static final String BUFFER_SIZE_CONF = "spark.fusion.optimizing-log.buffer-size";
  static final String TASK_FLUSH_TIMEOUT_CONF = "spark.fusion.optimizing-log.task-flush-timeout";
  private static final String DEFAULT_BUFFER_SIZE = "64m";
  private static final String DEFAULT_TASK_FLUSH_TIMEOUT = "5s";
  private static final long DRIVER_FLUSH_INTERVAL_MS = 1000L;
  private static final long EXECUTOR_FLUSH_INTERVAL_MS = 200L;
  private static final String ENDPOINT_NAME = "amoro-optimizing-log";
  // SparkContext.DRIVER_IDENTIFIER, which Spark does not expose: the driver JVM's executor id,
  // also in local mode.
  private static final String DRIVER_EXECUTOR_ID = "driver";

  // This JVM's shipper: to AMS on the driver, to the driver on an executor.
  private static volatile LogShipper shipper;
  private static volatile AmsTransport amsTransport;
  private static volatile long taskFlushTimeoutMs;

  private SparkLogShipping() {}

  /** Starts shipping on the driver. A failure is logged; the optimizer then runs without it. */
  static synchronized void startOnDriver(String amsUrl, SparkConf conf) {
    try {
      SparkEnv env = SparkEnv.get();
      AmsTransport transport = new AmsTransport(amsUrl);
      LogShipper driverShipper =
          newShipper("driver", "AMS", transport, conf, DRIVER_FLUSH_INTERVAL_MS);
      env.rpcEnv().setupEndpoint(ENDPOINT_NAME, new LogEndpoint(env.rpcEnv(), driverShipper));
      amsTransport = transport;
      shipper = driverShipper;
      LogAppender.install();
    } catch (Throwable t) {
      LOG.warn("Optimizing logs will not be shipped to AMS: setting up log shipping failed", t);
    }
  }

  /** Gives the driver's AMS transport the token the optimizer registered with. */
  static void onTokenChange(String token) {
    AmsTransport transport = amsTransport;
    if (transport != null) {
      transport.token = token;
    }
  }

  /** Prepares shipping before a task, on the driver or an executor. Never throws. */
  static void beforeTask() {
    try {
      // Again should Log4j have been reconfigured since the last task.
      LogAppender.install();
      if (shipper == null) {
        startOnExecutor();
      }
    } catch (Throwable t) {
      LOG.warn("Optimizing log shipping is unavailable; the task runs without it", t);
    }
  }

  /**
   * Waits a bounded time for the lines logged so far to reach the next hop: AMS from the driver,
   * the driver from an executor. Never fails the task; lines left keep being sent.
   */
  static void afterTask() {
    LogShipper current = shipper;
    if (current == null) {
      return;
    }
    long timeoutMs = taskFlushTimeoutMs;
    if (!current.awaitDelivered(current.lastSeq(), timeoutMs)) {
      LOG.warn(
          "Optimizing logs of the task were not delivered within {} ms; still sending them",
          timeoutMs);
    }
  }

  private static synchronized void startOnExecutor() {
    if (shipper != null) {
      return;
    }
    SparkEnv env = SparkEnv.get();
    if (env == null || DRIVER_EXECUTOR_ID.equals(env.executorId())) {
      // The driver JVM, whose shipper failed to start; there is nothing to send through.
      return;
    }
    shipper =
        newShipper(
            "executor-" + env.executorId(),
            "the Spark driver",
            new DriverTransport(env),
            env.conf(),
            EXECUTOR_FLUSH_INTERVAL_MS);
  }

  private static LogShipper newShipper(
      String role, String target, LogShipper.Transport transport, SparkConf conf, long flushMs) {
    taskFlushTimeoutMs = conf.getTimeAsMs(TASK_FLUSH_TIMEOUT_CONF, DEFAULT_TASK_FLUSH_TIMEOUT);
    LogShipper newShipper =
        new LogShipper(
            role + "-" + UUID.randomUUID(),
            target,
            transport,
            conf.getSizeAsBytes(BUFFER_SIZE_CONF, DEFAULT_BUFFER_SIZE),
            flushMs);
    newShipper.start();
    return newShipper;
  }

  /** Hands the lines of compaction threads to this JVM's shipper. */
  static final class LogAppender extends AbstractAppender {

    private static final String NAME = "OptimizingLogShipping";
    private static final String AMORO_LOGGER = "org.apache.amoro";

    // The line format AMS stores and LogController parses. A contract with AMS, so it is kept in
    // code rather than in log4j2.xml.
    private static final String JSON_LOG_PATTERN =
        "{\"level\":\"%p\",\"time\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}{UTC}\",\"processId\":\"%X{processId}\",\"taskId\":\"%X{taskId}\",\"attempt\":\"%X{attempt}\",\"logger\":\"%c{1}\",\"message\":\"%enc{%m}{JSON}\",\"stackTrace\":\"%enc{%throwable{full}}{JSON}\"}";

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
      LogShipper current = shipper;
      if (current == null) {
        return;
      }
      ReadOnlyStringMap logContext = event.getContextData();
      String processId = logContext.getValue(OptimizingTaskLogContext.PROCESS_ID_KEY);
      if (processId == null) {
        return; // not a compaction thread
      }
      String taskId = logContext.getValue(OptimizingTaskLogContext.TASK_ID_KEY);
      Level level = event.getLevel();
      int priority =
          level.isMoreSpecificThan(Level.WARN)
              ? LogRecord.PRIORITY_HIGH
              : level.isMoreSpecificThan(Level.INFO)
                  ? LogRecord.PRIORITY_NORMAL
                  : LogRecord.PRIORITY_LOW;
      // Task id 0 is a driver line, which AMS writes to driver.log.
      current.add(
          new LogRecord(
              Long.parseLong(processId),
              taskId == null ? 0 : Integer.parseInt(taskId),
              priority,
              new String(getLayout().toByteArray(event), StandardCharsets.UTF_8)));
    }
  }

  /** Message an executor sends to the driver's {@link LogEndpoint}. */
  static final class LogBatch implements Serializable {
    private static final long serialVersionUID = 1L;

    final ArrayList<LogRecord> records;

    LogBatch(List<LogRecord> records) {
      this.records = new ArrayList<>(records);
    }
  }

  /** Driver endpoint: buffers each executor batch for AMS, then confirms it. */
  static final class LogEndpoint implements ThreadSafeRpcEndpoint {
    private final RpcEnv rpcEnv;
    private final LogShipper driverShipper;

    LogEndpoint(RpcEnv rpcEnv, LogShipper driverShipper) {
      this.rpcEnv = rpcEnv;
      this.driverShipper = driverShipper;
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
          driverShipper.forward(((LogBatch) message).records);
          context.reply(Boolean.TRUE);
          return BoxedUnit.UNIT;
        }
      };
    }
  }

  /** Driver: sends batches to AMS. */
  static final class AmsTransport implements LogShipper.Transport {
    // Log calls get their own client pool (pools are cached per URL) with bounded waits, so an AMS
    // that stops answering does not hold up log delivery forever.
    private static final String CALL_TIMEOUTS = "socketTimeout=30000&connectTimeout=10000";

    private final String amsUrl;
    volatile String token;

    AmsTransport(String amsUrl) {
      this.amsUrl = amsUrl + (amsUrl.contains("?") ? "&" : "?") + CALL_TIMEOUTS;
    }

    @Override
    public void send(List<LogRecord> batch) throws Exception {
      String currentToken = token;
      if (currentToken == null) {
        throw new IllegalStateException("The optimizer is not registered with AMS yet");
      }
      List<OptimizingLogLine> lines = new ArrayList<>(batch.size());
      for (LogRecord record : batch) {
        OptimizingLogLine line =
            new OptimizingLogLine(
                new OptimizingTaskId(record.processId, record.taskId), record.ndjson);
        if (!record.isGapMarker()) {
          line.setSenderId(record.senderId);
          line.setSeq(record.seq);
        }
        lines.add(line);
      }
      OptimizingClientPools.getClient(amsUrl).appendLogs(currentToken, lines);
    }

    @Override
    public boolean isUnsupported(Throwable error) {
      // An AMS older than appendLogs.
      return error instanceof TApplicationException
          && ((TApplicationException) error).getType() == TApplicationException.UNKNOWN_METHOD;
    }
  }

  /** Executor: sends batches to the driver's {@link LogEndpoint}. */
  static final class DriverTransport implements LogShipper.Transport {
    private static final ClassTag<Boolean> ACK = ClassTag$.MODULE$.apply(Boolean.class);

    private final SparkEnv env;
    private final RpcTimeout askTimeout =
        new RpcTimeout(
            FiniteDuration.apply(10, TimeUnit.SECONDS), "amoro.optimizing.log.askTimeout");
    // Looked up on the send thread, so a slow lookup never holds up a task.
    private RpcEndpointRef driver;

    DriverTransport(SparkEnv env) {
      this.env = env;
    }

    @Override
    public void send(List<LogRecord> batch) {
      if (driver == null) {
        RpcAddress address =
            RpcAddress.apply(
                env.conf().get("spark.driver.host"),
                Integer.parseInt(env.conf().get("spark.driver.port")));
        driver = env.rpcEnv().setupEndpointRef(address, ENDPOINT_NAME);
      }
      driver.askSync(new LogBatch(batch), askTimeout, ACK);
    }
  }
}
