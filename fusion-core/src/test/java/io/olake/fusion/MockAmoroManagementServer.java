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

import io.olake.fusion.api.AmoroException;
import io.olake.fusion.api.OptimizerRegisterInfo;
import io.olake.fusion.api.OptimizingService;
import io.olake.fusion.api.OptimizingTask;
import io.olake.fusion.api.OptimizingTaskId;
import io.olake.fusion.api.OptimizingTaskResult;
import org.apache.amoro.shade.thrift.org.apache.thrift.TException;
import org.apache.amoro.shade.thrift.org.apache.thrift.TMultiplexedProcessor;
import org.apache.amoro.shade.thrift.org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.amoro.shade.thrift.org.apache.thrift.protocol.TProtocolFactory;
import org.apache.amoro.shade.thrift.org.apache.thrift.server.TServer;
import org.apache.amoro.shade.thrift.org.apache.thrift.server.TThreadPoolServer;
import org.apache.amoro.shade.thrift.org.apache.thrift.transport.TServerSocket;
import org.apache.amoro.shade.thrift.org.apache.thrift.transport.TTransportException;
import org.apache.amoro.shade.thrift.org.apache.thrift.transport.layered.TFramedTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MockAmoroManagementServer implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(MockAmoroManagementServer.class);
  public static final String TEST_CATALOG_NAME = "test_catalog";
  public static final String TEST_DB_NAME = "test_db";

  private int port;
  private int retry = 10;
  private boolean started = false;
  private final OptimizerManagerHandler optimizerManagerHandler = new OptimizerManagerHandler();

  private TServer server;

  private static final MockAmoroManagementServer INSTANCE = new MockAmoroManagementServer();

  public static MockAmoroManagementServer getInstance() {
    if (!INSTANCE.isStarted()) {
      INSTANCE.start();
    }
    return INSTANCE;
  }

  public static String getHadoopSite() {
    return Base64.getEncoder()
        .encodeToString("<configuration></configuration>".getBytes(StandardCharsets.UTF_8));
  }

  public String getServerUrl() {
    return "thrift://127.0.0.1:" + port;
  }

  public MockAmoroManagementServer() {
    this.port = randomPort();
  }

  int randomPort() {
    // create a random port between 14000 - 18000
    int port = new Random().nextInt(4000);
    return port + 14000;
  }

  public void start() {
    Thread t = new Thread(this);
    t.start();
    started = true;
  }

  public void stopAndCleanUp() {
    if (server != null) {
      server.stop();
    }
    started = false;
  }

  public boolean isStarted() {
    return started;
  }

  public OptimizerManagerHandler optimizerHandler() {
    return optimizerManagerHandler;
  }

  public int port() {
    return port;
  }

  @Override
  public void run() {
    try {
      TServerSocket socket = new TServerSocket(port);
      TMultiplexedProcessor processor = new TMultiplexedProcessor();
      OptimizingService.Processor<OptimizerManagerHandler> optimizerManProcessor =
          new OptimizingService.Processor<>(optimizerManagerHandler);
      processor.registerProcessor("OptimizeManager", optimizerManProcessor);

      final long maxMessageSize = 100 * 1024 * 1024L;
      final TProtocolFactory protocolFactory;
      final TProtocolFactory inputProtoFactory;
      protocolFactory = new TBinaryProtocol.Factory();
      inputProtoFactory = new TBinaryProtocol.Factory(true, true, maxMessageSize, maxMessageSize);

      SynchronousQueue<Runnable> executorQueue = new SynchronousQueue<>();
      AtomicInteger threadCount = new AtomicInteger(0);
      ThreadPoolExecutor threadPoolExecutor =
          new ThreadPoolExecutor(
              1,
              10,
              60,
              TimeUnit.SECONDS,
              executorQueue,
              r -> {
                Thread thread = new Thread(r);
                String threadName = "ams-pool-" + threadCount.incrementAndGet();
                thread.setName(threadName);
                LOG.info("Mock AMS create thread: {}", threadName);
                return thread;
              },
              new ThreadPoolExecutor.AbortPolicy());

      TThreadPoolServer.Args args =
          new TThreadPoolServer.Args(socket)
              .processor(processor)
              .transportFactory(new TFramedTransport.Factory())
              .protocolFactory(protocolFactory)
              .inputProtocolFactory(inputProtoFactory)
              .executorService(threadPoolExecutor);
      server = new TThreadPoolServer(args);
      server.serve();

      LOG.info("In-memory AMS started.");
    } catch (TTransportException e) {
      if (e.getCause() instanceof BindException) {
        if (--retry < 0) {
          throw new IllegalStateException(e);
        } else {
          port = randomPort();
          LOG.info("Address already in use, port {}, and retry a new port.", port);
          run();
        }
      } else {
        throw new IllegalStateException(e);
      }
    }
  }

  public static class OptimizerManagerHandler implements OptimizingService.Iface {

    private final Map<String, OptimizerRegisterInfo> registeredOptimizers =
        new ConcurrentHashMap<>();
    private final Queue<OptimizingTask> pendingTasks = new ArrayBlockingQueue<>(100);
    private final Map<String, Map<Integer, OptimizingTaskId>> executingTasks =
        new ConcurrentHashMap<>();
    private final Map<String, List<OptimizingTaskResult>> completedTasks =
        new ConcurrentHashMap<>();

    public void cleanUp() {}

    @Override
    public void ping() {}

    @Override
    public void touch(String authToken) throws TException {
      checkToken(authToken);
    }

    @Override
    public OptimizingTask pollTask(String authToken, int threadId) throws TException {
      checkToken(authToken);
      return pendingTasks.poll();
    }

    @Override
    public void ackTask(String authToken, int threadId, OptimizingTaskId taskId) throws TException {
      checkToken(authToken);
      if (!executingTasks.containsKey(authToken)) {
        executingTasks.putIfAbsent(authToken, new ConcurrentHashMap<>());
      }
      Map<Integer, OptimizingTaskId> executingTasksMap = executingTasks.get(authToken);
      if (executingTasksMap.containsKey(threadId)) {
        throw new AmoroException(
            ErrorCodes.TASK_RUNTIME_ERROR_CODE,
            "DuplicateTask",
            String.format(
                "Optimizer:%s" + " thread:%d is executing another task", authToken, threadId));
      }
      executingTasksMap.put(threadId, taskId);
    }

    @Override
    public void completeTask(String authToken, OptimizingTaskResult taskResult) throws TException {
      checkToken(authToken);
      executingTasks.get(authToken).remove(taskResult.getThreadId());
      if (!completedTasks.containsKey(authToken)) {
        completedTasks.putIfAbsent(authToken, new CopyOnWriteArrayList<>());
      }
      List<OptimizingTaskResult> completeTaskList = completedTasks.get(authToken);
      completeTaskList.add(taskResult);
    }

    @Override
    public String authenticate(OptimizerRegisterInfo registerInfo) throws TException {
      String token = UUID.randomUUID().toString();
      registeredOptimizers.put(token, registerInfo);
      return token;
    }

    @Override
    public boolean cancelProcess(long processId) throws TException {
      return false;
    }

    public Map<String, OptimizerRegisterInfo> getRegisteredOptimizers() {
      return registeredOptimizers;
    }

    public boolean offerTask(OptimizingTask task) {
      return pendingTasks.offer(task);
    }

    public Queue<OptimizingTask> getPendingTasks() {
      return pendingTasks;
    }

    public Map<String, Map<Integer, OptimizingTaskId>> getExecutingTasks() {
      return executingTasks;
    }

    public Map<String, List<OptimizingTaskResult>> getCompletedTasks() {
      return completedTasks;
    }

    private void checkToken(String token) throws AmoroException {
      if (!registeredOptimizers.containsKey(token)) {
        throw new AmoroException(
            ErrorCodes.PLUGIN_RETRY_AUTH_ERROR_CODE, "unknown token", "unknown token");
      }
    }
  }
}
