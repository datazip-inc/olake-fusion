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

package io.olake.fusion.server.optimizing;

import io.olake.fusion.BasicTableTestHelper;
import io.olake.fusion.OptimizerProperties;
import io.olake.fusion.ServerTableIdentifier;
import io.olake.fusion.TableFormat;
import io.olake.fusion.TableTestHelper;
import io.olake.fusion.api.OptimizerRegisterInfo;
import io.olake.fusion.catalog.BasicCatalogTestHelper;
import io.olake.fusion.catalog.CatalogTestHelper;
import io.olake.fusion.exception.ForbiddenException;
import io.olake.fusion.io.DataTestHelpers;
import io.olake.fusion.optimizing.OptimizingType;
import io.olake.fusion.optimizing.RewriteFilesInput;
import io.olake.fusion.optimizing.RewriteStageTask;
import io.olake.fusion.process.ProcessStatus;
import io.olake.fusion.resource.ResourceGroup;
import io.olake.fusion.server.optimizing.TaskRuntime.Status;
import io.olake.fusion.server.persistence.PersistentBase;
import io.olake.fusion.server.persistence.SqlSessionFactoryProvider;
import io.olake.fusion.server.persistence.mapper.OptimizingProcessMapper;
import io.olake.fusion.server.persistence.mapper.TableProcessMapper;
import io.olake.fusion.server.process.TableProcessMeta;
import io.olake.fusion.server.resource.OptimizerThread;
import io.olake.fusion.server.resource.QuotaProvider;
import io.olake.fusion.server.table.AMSTableTestBase;
import io.olake.fusion.server.table.DefaultTableRuntime;
import io.olake.fusion.server.utils.CompressUtil;
import io.olake.fusion.table.TableProperties;
import io.olake.fusion.utils.SerializationUtil;
import org.apache.amoro.shade.guava32.com.google.common.collect.Lists;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.ibatis.session.SqlSession;
import org.apache.iceberg.Table;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A process in flight when the server is upgraded keeps its task inputs in Postgres in the format
 * of the release that planned it. When the new release cannot read them, recovering the process
 * must close it without a commit and leave the table IDLE, so the next cron plans it again.
 */
@RunWith(Parameterized.class)
public class TestOptimizingQueueRecovery extends AMSTableTestBase {

  /** {@code serialVersionUID} of {@code RewriteFilesInput} before the format change (format 1). */
  private static final long FORMAT_1_SERIAL_VERSION_UID = -696610318564333923L;

  private static final long MAX_POLLING_TIME = 5000;
  private static final AtomicInteger QUEUES = new AtomicInteger();

  private final Executor planExecutor = Executors.newSingleThreadExecutor();
  private final QuotaProvider quotaProvider = resourceGroup -> 1;
  private final OptimizerThread optimizerThread =
      new OptimizerThread(1, null) {
        @Override
        public String getToken() {
          return "recovery-test";
        }
      };

  public TestOptimizingQueueRecovery(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper, true);
  }

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {new BasicCatalogTestHelper(TableFormat.ICEBERG), new BasicTableTestHelper(false, true)}
    };
  }

  /**
   * The shared test metastore may still hold tables of other test classes, so dropping its database
   * can fail after the catalog is gone; that does not concern these tests.
   */
  @After
  @Override
  public void dispose() throws TException {
    try {
      super.dispose();
    } catch (InvalidOperationException e) {
      // tables of other test classes are left in the shared metastore database
    }
  }

  @Test
  public void testOptimizerOfAnotherTaskFormatIsRejected() {
    // an optimizer of a release before the task format change does not report a format
    OptimizerRegisterInfo registerInfo = new OptimizerRegisterInfo();
    Map<String, String> properties = new HashMap<>();
    properties.put(OptimizerProperties.OPTIMIZER_HEART_BEAT_INTERVAL, "100");
    properties.put(OptimizerProperties.OPTIMIZER_TAG, OptimizerProperties.OPTIMIZER_TAG_VALUE);
    registerInfo.setProperties(properties);
    registerInfo.setThreadCount(1);
    registerInfo.setMemoryMb(1024);
    registerInfo.setGroupName(defaultResourceGroup().getName());
    registerInfo.setResourceId("format-test");
    registerInfo.setStartTime(System.currentTimeMillis());
    Assert.assertThrows(
        ForbiddenException.class, () -> optimizingService().authenticate(registerInfo));

    properties.put(OptimizerProperties.OPTIMIZER_TASK_FORMAT, "1");
    Assert.assertThrows(
        ForbiddenException.class, () -> optimizingService().authenticate(registerInfo));

    properties.put(
        OptimizerProperties.OPTIMIZER_TASK_FORMAT, OptimizerProperties.OPTIMIZER_TASK_FORMAT_VALUE);
    String token = optimizingService().authenticate(registerInfo);
    Assert.assertNotNull(token);
    optimizingService().deleteOptimizer(defaultResourceGroup().getName(), "format-test");
  }

  @Test
  public void testFormatOneInputsCloseTheProcess() throws Exception {
    assertRecoveryClosesProcess(TestOptimizingQueueRecovery::asFormatOne);
  }

  @Test
  public void testUnreadableInputsCloseTheProcess() throws Exception {
    assertRecoveryClosesProcess(stored -> "not a gzip stream".getBytes(StandardCharsets.UTF_8));
  }

  private void assertRecoveryClosesProcess(Function<byte[], byte[]> corruptStoredInputs)
      throws Exception {
    DefaultTableRuntime tableRuntime = initTableWithFiles();
    Table table = tableService().loadTable(tableRuntime.getTableIdentifier()).table();
    long snapshotBefore = table.currentSnapshot().snapshotId();

    // plan a process and leave its task in flight
    OptimizingQueue queue = buildQueue(tableRuntime);
    long processId;
    try {
      TaskRuntime<?> task = queue.pollTask(optimizerThread, MAX_POLLING_TIME);
      Assert.assertNotNull("a process was planned", task);
      processId = task.getTaskId().getProcessId();
      Assert.assertTrue(tableRuntime.getOptimizingStatus().isProcessing());
    } finally {
      queue.dispose();
    }

    // what an earlier release left in the database
    replaceStoredInputs(processId, corruptStoredInputs.apply(readStoredInputs(processId)));

    // server restart: the queue recovers the process from the database
    OptimizingQueue restarted = buildQueue(tableRuntime);
    try {
      Assert.assertEquals(OptimizingStatus.IDLE, tableRuntime.getOptimizingStatus());
      TableProcessMeta process =
          DB.read(TableProcessMapper.class, mapper -> mapper.getProcessMeta(processId));
      Assert.assertEquals(ProcessStatus.CLOSED, process.getStatus());
      Assert.assertTrue(
          process.getFailMessage(),
          process.getFailMessage().contains("could not read the stored tasks"));
      List<TaskRuntime<RewriteStageTask>> tasks =
          DB.read(
              OptimizingProcessMapper.class,
              mapper ->
                  mapper.selectTaskRuntimes(tableRuntime.getTableIdentifier().getId(), processId));
      Assert.assertFalse(tasks.isEmpty());
      tasks.forEach(t -> Assert.assertEquals(Status.CANCELED, t.getStatus()));
      Assert.assertTrue(restarted.collectTasks().isEmpty());

      // nothing was committed
      table.refresh();
      Assert.assertEquals(snapshotBefore, table.currentSnapshot().snapshotId());

      // the next cron plans the table again
      tableRuntime.markAsPending(OptimizingType.MINOR);
      TaskRuntime<?> replanned = restarted.pollTask(optimizerThread, MAX_POLLING_TIME);
      Assert.assertNotNull("the table is planned again", replanned);
      Assert.assertNotEquals(processId, replanned.getTaskId().getProcessId());
      Assert.assertTrue(tableRuntime.getOptimizingStatus().isProcessing());
    } finally {
      restarted.dispose();
    }
  }

  /**
   * Turns the stored inputs into format 1: the same stream with the format 1 {@code
   * serialVersionUID} of {@code RewriteFilesInput}, as an earlier release wrote it.
   */
  private static byte[] asFormatOne(byte[] storedInputs) {
    byte[] serialized = CompressUtil.unGzip(storedInputs);
    byte[] className = RewriteFilesInput.class.getName().getBytes(StandardCharsets.UTF_8);
    int at = indexOf(serialized, className);
    Assert.assertTrue("class descriptor of RewriteFilesInput in the stored inputs", at >= 0);
    int uidAt = at + className.length;
    Assert.assertEquals(
        RewriteFilesInput.FORMAT_VERSION, ByteBuffer.wrap(serialized, uidAt, 8).getLong());
    ByteBuffer.wrap(serialized, uidAt, 8).putLong(FORMAT_1_SERIAL_VERSION_UID);
    try {
      SerializationUtil.simpleDeserialize(serialized);
      Assert.fail("format 1 inputs must not be readable");
    } catch (IllegalArgumentException expected) {
      // java.io.InvalidClassException: local class incompatible
    }
    return CompressUtil.gzip(serialized);
  }

  private static int indexOf(byte[] data, byte[] pattern) {
    outer:
    for (int i = 0; i + pattern.length <= data.length; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private byte[] readStoredInputs(long processId) {
    List<byte[]> stored =
        DB.read(OptimizingProcessMapper.class, mapper -> mapper.selectProcessInputFiles(processId));
    Assert.assertEquals(1, stored.size());
    Map<Integer, RewriteFilesInput> inputs =
        SerializationUtil.simpleDeserialize(CompressUtil.unGzip(stored.get(0)));
    Assert.assertFalse(inputs.isEmpty());
    return stored.get(0);
  }

  private static void replaceStoredInputs(long processId, byte[] bytes) throws Exception {
    try (SqlSession session = SqlSessionFactoryProvider.getInstance().get().openSession(true);
        Connection connection = session.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE optimizing_process_state SET rewrite_input = ? WHERE process_id = ?")) {
      statement.setBytes(1, bytes);
      statement.setLong(2, processId);
      Assert.assertEquals(1, statement.executeUpdate());
    }
  }

  private OptimizingQueue buildQueue(DefaultTableRuntime tableRuntime) {
    return new OptimizingQueue(
        CATALOG_MANAGER,
        new ResourceGroup.Builder("recovery-" + QUEUES.incrementAndGet(), "local").build(),
        quotaProvider,
        planExecutor,
        Collections.singletonList(tableRuntime),
        1);
  }

  private DefaultTableRuntime initTableWithFiles() {
    ServerTableIdentifier identifier =
        tableManager()
            .getServerTableIdentifier(TableTestHelper.TEST_TABLE_ID.buildTableIdentifier());
    Table table = tableService().loadTable(identifier).table();
    table
        .updateProperties()
        .set(TableProperties.SELF_OPTIMIZING_MINOR_TRIGGER_CRON, "0 0 * * *")
        .commit();
    // enough fragment files for a minor optimizing
    for (int i = 1; i <= 13; i++) {
      DataTestHelpers.writeAndCommitDataFiles(
          table,
          Lists.newArrayList(
              tableTestHelper().generateTestRecord(i, "name" + i, 0L, "2022-01-01T12:00:00")));
    }
    DefaultTableRuntime tableRuntime =
        (DefaultTableRuntime) tableService().getRuntime(identifier.getId());
    tableRuntime.refresh(tableService().loadTable(identifier));
    tableRuntime.markAsPending(OptimizingType.MINOR);
    return tableRuntime;
  }

  private static final Database DB = new Database();

  private static class Database extends PersistentBase {
    <T, R> R read(Class<T> mapperClz, Function<T, R> func) {
      return getAs(mapperClz, func);
    }
  }
}
