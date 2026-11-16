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
import org.apache.amoro.optimizer.common.OptimizerConfig;
import org.apache.amoro.optimizer.spark.OptimizingLogSender.LogLine;
import org.apache.amoro.optimizer.spark.OptimizingLogSender.TaskKey;
import org.apache.amoro.shade.guava32.com.google.common.base.Preconditions;
import org.apache.amoro.shade.thrift.org.apache.thrift.TApplicationException;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>Shipping never blocks a compaction thread, and never fails a task: logs are not worth failing
 * a compaction for. Logging only buffers a line; an ERROR line also asks for an immediate send. At
 * its end, an executor task queues an end marker behind its lines and returns at once, so its Spark
 * core is free for the next task. Before the driver reports the task's result to AMS, it waits a
 * bounded time until AMS accepted the task's lines (the executor's, up to the end marker, and the
 * driver's own), so a finished run normally shows its full log.
 *
 * <p>Buffers are bounded, so an unreachable driver or AMS cannot run a JVM out of memory. Lines
 * that do not fit are dropped and counted per task; the driver then reports the loss to AMS, which
 * writes it to the run's driver.log and reports it in telemetry. An AMS without {@code appendLogs}
 * (an older version) turns shipping off.
 */
final class SparkOptimizingLogSupport {

  private static final Logger LOG = LoggerFactory.getLogger(SparkOptimizingLogSupport.class);

  // How long the driver waits at a task's end for its lines to reach AMS. The result goes to AMS
  // either way; lines that are late are still delivered afterwards.
  private static final long TASK_DELIVERY_WAIT_MS = TimeUnit.SECONDS.toMillis(10);
  // Buffer limits, well below the 1 GB default heap of the driver and of an executor. The driver
  // buffers its executors' lines too, so it gets more.
  private static final long EXECUTOR_BUFFER_BYTES = 32L * 1024L * 1024L;
  private static final long DRIVER_BUFFER_BYTES = 64L * 1024L * 1024L;
  private static final String ENDPOINT_NAME = "amoro-optimizing-log";

  // Set on the driver only. Executors send to the driver through DriverSender.
  private static volatile AmsSender amsSender;

  private SparkOptimizingLogSupport() {}

  /** Starts log shipping on the driver. Throws if it cannot. */
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
   * Prepares log shipping before a task, on the driver or an executor. Never throws: a task runs
   * even if its lines cannot be shipped.
   */
  static void beforeTask() {
    try {
      // Installs once per JVM, and again should Log4j have been reconfigured since.
      LogAppender.install();
      if (amsSender == null) {
        // An executor JVM. In Spark local mode tasks run in the driver JVM, which sends to AMS
        // itself.
        DriverSender.INSTANCE.connect();
      }
    } catch (Throwable t) {
      LOG.warn("Failed to prepare optimizing log shipping; the task runs without it", t);
    }
  }

  /** On the driver, before a task: forgets an end marker left by an earlier run of the task. */
  static void beforeDriverTask(OptimizingTaskId taskId) {
    beforeTask();
    amsSender.forgetTaskEnd(new TaskKey(taskId.getProcessId(), taskId.getTaskId()));
  }

  /**
   * At the end of a task's Spark function: queues the task's end marker behind its lines and asks
   * for them to be sent. Does not wait.
   */
  static void afterExecutorTask(long processId, int taskId) {
    OptimizingLogSender sender = sender();
    sender.add(LogLine.taskEnd(processId, taskId, sender.takeDropped(processId, taskId)));
    sender.flushAsync();
  }

  /**
   * On the driver, before the task's result goes to AMS: waits a bounded time for the task's lines
   * to reach AMS, and reports lines that were dropped. Never changes the result.
   *
   * @param functionReturned true if the task's Spark function returned, so its end marker is on the
   *     way; false if the Spark job failed, and only the driver's own lines are waited for
   */
  static void afterDriverTask(OptimizingTaskId taskId, boolean functionReturned) {
    try {
      amsSender.awaitTask(
          new TaskKey(taskId.getProcessId(), taskId.getTaskId()),
          functionReturned,
          TASK_DELIVERY_WAIT_MS);
    } catch (Throwable t) {
      LOG.warn("Failed to check delivery of optimizing logs of task {}", taskId, t);
    }
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
      if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
        // Sent now rather than on the next tick, so a crash right after it is less likely to
        // lose it.
        sender.flushAsync();
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

  /**
   * Driver endpoint: hands each executor batch to the AMS sender, and replies whether it took it. A
   * batch that does not fit in the driver's buffer is refused, and the executor sends it again.
   */
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
          context.reply(sender.offerAll(((LogBatch) message).lines));
          return BoxedUnit.UNIT;
        }
      };
    }
  }

  /** Driver: sends lines to AMS, and tracks which tasks' lines AMS accepted. */
  static final class AmsSender extends OptimizingLogSender {
    // End markers that arrived and that no task waits for yet. Bounded, in case a marker is never
    // waited for, such as one from a speculative run of a task.
    private static final int MAX_PENDING_TASK_ENDS = 10_000;

    // Log calls get their own client pool (pools are cached per URL) with bounded waits, so an AMS
    // that stops answering does not hold up log delivery forever.
    private static final String CALL_TIMEOUTS = "socketTimeout=30000&connectTimeout=10000";

    private final String amsUrl;
    private volatile String token;
    // Guarded by lock. For each task end marker: the number of lines buffered before it, and the
    // lines its executor dropped.
    private final Map<TaskKey, long[]> taskEnds =
        new LinkedHashMap<TaskKey, long[]>() {
          @Override
          protected boolean removeEldestEntry(Map.Entry<TaskKey, long[]> eldest) {
            return size() > MAX_PENDING_TASK_ENDS;
          }
        };

    AmsSender(String amsUrl) {
      super("AMS", TimeUnit.SECONDS.toMillis(1), DRIVER_BUFFER_BYTES);
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
      try {
        OptimizingClientPools.getClient(amsUrl).appendLogs(currentToken, lines);
      } catch (TApplicationException e) {
        if (e.getType() != TApplicationException.UNKNOWN_METHOD) {
          throw e;
        }
        LOG.warn(
            "AMS does not support optimizing log shipping (an older version); optimizing logs "
                + "are not stored until AMS is upgraded");
        disable();
      }
    }

    // The marker ends its task's lines here: it is not sent to AMS.
    @Override
    protected void onTaskEnd(LogLine marker) {
      taskEnds.put(marker.key(), new long[] {addedLines, marker.droppedLines});
      lock.notifyAll();
      flushAsync();
    }

    void forgetTaskEnd(TaskKey key) {
      synchronized (lock) {
        taskEnds.remove(key);
      }
    }

    /**
     * Waits up to {@code timeoutMs} until AMS accepted the task's lines: the driver's lines so far
     * and, if {@code expectTaskEnd}, the executor's lines up to the task's end marker. Reports
     * lines of the task that were dropped to AMS.
     */
    void awaitTask(TaskKey key, boolean expectTaskEnd, long timeoutMs) throws InterruptedException {
      long deadline = System.currentTimeMillis() + timeoutMs;
      flushAsync();
      long dropped;
      String late = null;
      synchronized (lock) {
        long linesToSend = addedLines;
        dropped = takeDropped(key.processId, 0) + takeDropped(key.processId, key.taskId);
        if (expectTaskEnd) {
          long[] taskEnd;
          while ((taskEnd = taskEnds.remove(key)) == null && waitUntil(deadline)) {}
          if (taskEnd == null) {
            late = "did not reach the Spark driver";
          } else {
            linesToSend = Math.max(linesToSend, taskEnd[0]);
            dropped += taskEnd[1];
          }
        }
        while (late == null && !disabled && sentLines < linesToSend) {
          if (!waitUntil(deadline)) {
            late = "did not reach AMS";
          }
        }
      }
      if (late != null) {
        LOG.warn(
            "Optimizing logs of task {} {} within {} ms; they are still sent",
            key,
            late,
            timeoutMs);
      }
      if (dropped > 0) {
        String report =
            String.format(
                "%d optimizing log lines of task %s were dropped because the optimizer's log "
                    + "buffer was full",
                dropped, key);
        LOG.warn(report);
        add(LogLine.unbounded(key.processId, OptimizingTaskLogContext.LOST_LINES_TASK_ID, report));
        flushAsync();
      }
    }

    // Under lock. False once the deadline passed.
    private boolean waitUntil(long deadline) throws InterruptedException {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return false;
      }
      lock.wait(remaining);
      return true;
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
      super("the Spark driver", 200L, EXECUTOR_BUFFER_BYTES);
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
      Preconditions.checkState(
          endpoint.askSync(new LogBatch(batch), askTimeout, ACK),
          "The Spark driver's log buffer is full");
    }
  }
}
