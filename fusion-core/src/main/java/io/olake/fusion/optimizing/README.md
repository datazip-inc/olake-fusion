# `io.olake.fusion.optimizing`

Shared types of one compaction ("optimizing") run: the task input and output that travel between the
server and the optimizer, the rewrite executor that the optimizer runs, and the metrics and state
records the server keeps.

## Role in Fusion

This package is used on **both sides**:

| Side | What it uses here |
| --- | --- |
| Server (`fusion-ams`) | `RewriteStageTask` (one task per bin of files), `RewriteFilesInput` / `RewriteFilesOutput`, `MetricsSummary`, `TableRuntimeOptimizingState`, `OptimizingType`, `TaskProperties` keys |
| Optimizer (`fusion-optimizer-common`, run by `fusion-optimizer-spark` in production) | `OptimizingExecutorFactory` → `IcebergRewriteExecutorFactory` → `IcebergRewriteExecutor`, `OptimizingDataReader`, `TaskProperties.getStructLikeCollections` |

Place in the lifecycle:

1. A cron fires and `TableRuntimeRefreshExecutor` (in `fusion-ams`) marks the table `PENDING`.
2. `OptimizingQueue.planInternal` builds an `IcebergOptimizingPlanner` (see [`plan`](plan/README.md)),
   which returns a list of `RewriteStageTask`, each holding one `RewriteFilesInput`.
3. The server serializes the input (`StagedTaskDescriptor.extractProtocolTask`) and hands it to the
   optimizer through Thrift.
4. The optimizer (`OptimizerExecutor.executeTask`) deserializes the input, loads the factory class
   named by the task property `task-executor-factory-impl`, runs `IcebergRewriteExecutor.execute()`
   and sends back a serialized `RewriteFilesOutput`.
5. The server stores the output on the task (`StagedTaskDescriptor.setOutputBytes`) and, once all
   tasks are done, `UnKeyedTableCommit` (in `fusion-ams`) commits all outputs in one Iceberg
   transaction.

The `OUTPUT_DIR` key in `TaskProperties` is a leftover that nothing in main code reads or sets.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizingType` | Enum `MINOR`, `MAJOR`, `FULL` (OLake-UI: Lite, Medium, Full). | `CommonPartitionEvaluator`, `TableRuntimeRefreshExecutor` |
| `TableOptimizing` | Umbrella interface with nested `OptimizingInput` / `OptimizingOutput` (both `Serializable`). The Javadoc says it "has not yet been formally used"; only the nested interfaces matter. | All inputs and outputs |
| `BaseOptimizingInput` | `OptimizingInput` with a free-form `options` map. | `RewriteFilesInput` |
| `RewriteFilesInput` | The task input: `rewrittenDataFiles`, `rePosDeletedDataFiles`, `readOnlyDeleteFiles`, `rewrittenDeleteFiles` and the `MixedTable`. `serialVersionUID = -696610318564333923L`. | `AbstractPartitionPlan.SplitTask.buildTask`, `GenericCombinedIcebergDataReader`, `UnKeyedTableCommit` |
| `RewriteFilesOutput` | The task output: new `DataFile[]`, new `DeleteFile[]` and a summary map. | `IcebergRewriteExecutor`, `RewriteStageTask`, `UnKeyedTableCommit` |
| `RewriteStageTask` | `StagedTaskDescriptor<RewriteFilesInput, RewriteFilesOutput, MetricsSummary>` plus the partition path. Recomputes the `MetricsSummary` whenever input or output changes. | `AbstractPartitionPlan`, `TaskRuntime`, `OptimizingQueue` |
| `OptimizingExecutor` / `OptimizingExecutorFactory` | Executor contract (`execute()`) and its factory (no-arg constructor, then `initialize(properties)`, then `createExecutor(input)`). | `OptimizerExecutor.executeTask` |
| `IcebergRewriteExecutorFactory` | The only factory used for Iceberg; named in every task by `IcebergPartitionPlan.buildTaskProperties`. | `IcebergRewriteExecutor` |
| `AbstractRewriteFilesExecutor` | Template of a rewrite task: convert deletes to position deletes for `rePosDeletedDataFiles`, rewrite `rewrittenDataFiles`, build a summary. | `OptimizingDataReader`, Iceberg `TaskWriter` / `FileWriter` |
| `IcebergRewriteExecutor` | Iceberg implementation: reader `GenericCombinedIcebergDataReader`, data writer `UnpartitionedWriter` or `GenericIcebergPartitionedFanoutWriter`, delete writer `IcebergFanoutPosDeleteWriter`. | `io.olake.fusion.io.reader`, `io.olake.fusion.io.writer` |
| `OptimizingDataReader` | `readData()` (rows that survive all deletes) and `readDeletedData()` (rows removed by deletes, with file path and position). | `GenericCombinedIcebergDataReader` |
| `TaskProperties` | Task property keys (`task-executor-factory-impl`, `process-id`, `extend-disk-storage`, memory/disk storage keys) and `getStructLikeCollections`. | Planner, optimizer |
| `MetricsSummary` | Input/output file counts, sizes and records of a task or a whole process; keys such as `input-data-files(rewrite)`, `output-data-files`. Jackson `@JsonIgnoreProperties(ignoreUnknown = true)`. | `RewriteStageTask`, `OptimizingQueue.getSummary`, dashboard |
| `OptimizingTaskSummary` | Small string map built by the executor (`data_file_cnt`, `eq_delete_file_cnt`, `executor_duration`, ...). | `AbstractRewriteFilesExecutor.resolverSummary` |
| `TableRuntimeOptimizingState` | JSON state of a table runtime: current and last-optimized snapshot ids, last Minor/Major/Full time, `lastOptimizingType` (stored as a String). | `DefaultTableRuntime` (`OPTIMIZING_STATE_KEY`) |
| `HealthScoreInfo` | Three per-partition scores: small file (max 40), equality delete (max 40), position delete (max 20). | `CommonPartitionEvaluator.getHealthScore`, `PendingInput` |
| `OptimizingCommitterFactory` | Committer contract. Not used by the Iceberg commit path, which is `UnKeyedTableCommit` in `fusion-ams`. | none |

## How it works

### Input layout (built on the server)

`AbstractPartitionPlan.SplitTask.buildTask` fills a `RewriteFilesInput` like this:

| Field | Meaning | Fate at commit |
| --- | --- | --- |
| `rewrittenDataFiles` | Data files to read (with deletes applied) and write again. | Removed |
| `rePosDeletedDataFiles` | Data files kept as they are; only the rows their deletes remove are turned into new position deletes. | Kept |
| `rewrittenDeleteFiles` | Delete files that apply only to data files in this plan. | Removed (unless a failed task also used them) |
| `readOnlyDeleteFiles` | Delete files that also apply to data files *not* in this plan (the plan's `reservedDeleteFiles`). Applied while reading, never removed. | Kept |

### Execution (on the optimizer)

`AbstractRewriteFilesExecutor.execute()`:

1. If `rePosDeletedDataFiles` is not empty, `equalityToPosition()` iterates
   `dataReader.readDeletedData()` and writes one `PositionDelete` (file path, row position) per
   deleted row through `posWriter()`.
2. If `rewrittenDataFiles` is not empty, `rewriterDataFiles()` iterates `dataReader.readData()` and
   writes each surviving record through `dataWriter()`.
3. `dataReader.close()` runs in a `finally` block. Both iterators are opened with
   try-with-resources, and the writers are closed in `finally`.
4. `resolverSummary(...)` builds an `OptimizingTaskSummary`; the result is
   `new RewriteFilesOutput(dataFiles, deleteFiles, summary)`.

All file access runs inside `io.doAs(...)` (`AuthenticatedFileIO`).

`IcebergRewriteExecutor` specifics:

- **Reader**: `GenericCombinedIcebergDataReader` with the table schema, `table.spec()`, the name
  mapping from `schema.name-mapping.default` and the process id from `TaskProperties.getProcessId`
  (used as the delete-cache group). It applies a position delete only to the task's own data files,
  and an equality delete to a row only when the delete file's `dataSequenceNumber()` is **strictly
  greater** than the data file's `dataSequenceNumber()` (see `CombinedDeleteFilter`).
  `readDeletedData()` returns rows matched by an equality **or** a position delete.
- **Data writer**: `OutputFileFactory.builderFor(table, table.spec().specId(), 0)`; an
  `UnpartitionedWriter` for unpartitioned specs, else `GenericIcebergPartitionedFanoutWriter`.
  Output is always written with the table's **current** spec.
- **Target size**: `targetSize()` reads `self-optimizing.target-size` from the table properties
  (default `134217728`, 128 MB; OLake-UI sets 512 MB). If the total size of `rewrittenDataFiles` is
  below the target, it returns `Long.MAX_VALUE`, so the task writes one file instead of several
  (Amoro issue 3645).
- **Delete writer**: `IcebergFanoutPosDeleteWriter`, using the spec of the first input file
  (`fileSpec()`), the partition of the first input file (`partition()`) and full metrics for the
  `file_path` and `pos` columns (`fullMetricAppenderFactory`). It writes one position delete file
  per referenced data file, named by `TableFileUtil.optimizingPosDeleteFileName`, which lets the
  planner recognize optimizer-written delete files later.
- **File formats**: data format from `write.format.default` (`DEFAULT_FILE_FORMAT`), delete format
  from `write.delete.format.default`, falling back to the data format.

### Task properties

| Key | Set by | Used by |
| --- | --- | --- |
| `task-executor-factory-impl` | `IcebergPartitionPlan.buildTaskProperties` | `OptimizerExecutor.executeTask` (loads the class with `DynConstructors`) |
| `process-id` | `OptimizerExecutor.fillTaskProperties` | `IcebergRewriteExecutor.dataReader` (delete cache group; default `unknown`) |
| `extend-disk-storage` (default `false`), memory storage size (default 512 MB), disk storage path | `OptimizerExecutor.fillTaskProperties` from the optimizer config | `TaskProperties.getStructLikeCollections` (spill map for equality delete keys) |

### Server-side bookkeeping

- `RewriteStageTask.calculateSummary()` builds a `MetricsSummary(input)` and, when an output exists,
  sets the `newData*` and `newDelete*` fields. Note: in `MetricsSummary(RewriteFilesInput)`,
  `rePosDeletedDataFiles` are counted as `rewritePosData*`, shown as `input-data-files(read-only)`.
- `MetricsSummary(Collection<MetricsSummary>)` sums task summaries into a process summary.
- `TableRuntimeOptimizingState` is the per-table JSON blob; `lastOptimizingType` is a String so that
  old blobs stay readable.

## State, threads and concurrency

- One executor instance runs one task on one optimizer thread. Executors hold per-task state
  (`input`, `dataReader`) and are not shared.
- `StagedTaskDescriptor.output` and `summary` are `volatile`; they are written by the server thread
  that receives a task result and read by the commit and dashboard threads.
- `MetricsSummary` and `OptimizingTaskSummary` are plain mutable objects; do not share them across
  threads while they are being filled.

## Contracts

- **Serialization (must stay compatible across versions).** `RewriteFilesInput` and
  `RewriteFilesOutput` are Java-serialized with `SerializationUtil.simpleSerialize` (plain
  `ObjectOutputStream`). The same bytes are sent to the optimizer **and** stored gzip-compressed in
  PostgreSQL (`TaskFilesPersistence`, `Object2ByteArrayConvert`) so a restarted server can recover
  a running process. The server and the Spark image may run different builds. Therefore:
  - do not rename, remove or retype fields of `RewriteFilesInput`, `BaseOptimizingInput` or
    `RewriteFilesOutput`;
  - `RewriteFilesInput` pins `serialVersionUID`; `RewriteFilesOutput` and `BaseOptimizingInput` do
    **not**, so any change to their shape changes the default UID and breaks old bytes;
  - the input also carries the `MixedTable` (a `BasicUnkeyedTable`), so changes to that class
    graph affect the wire format too;
  - old `org.apache.amoro.*` class names are mapped by `LegacyAwareObjectInputStream`.
- **Factory loading.** The optimizer instantiates the class named in `task-executor-factory-impl`
  with a no-arg constructor. Renaming `IcebergRewriteExecutorFactory` breaks tasks persisted or
  planned by an older server.
- **`MetricsSummary` keys** (`input-data-files(rewrite)`, `output-data-size`, ...) are shown in the
  process summary that OLake-UI receives through the forwarded optimizing-process APIs. Changing
  them changes that response.
- **Table properties read here**: `self-optimizing.target-size`, `write.format.default`,
  `write.delete.format.default`, `schema.name-mapping.default`, plus everything `GenericAppenderFactory.setAll(table.properties())` honours (for example Parquet settings).
- **Iceberg APIs** (1.7.2): `GenericAppenderFactory`, `OutputFileFactory`, `UnpartitionedWriter`,
  `PositionDelete`, `MetadataColumns.FILE_PATH` / `ROW_POSITION`, `DeleteWriteResult`,
  `MetricsModes.Full`.

## Extending or changing it

- A new rewrite behaviour for Iceberg belongs in `IcebergRewriteExecutor` (override
  `dataReader()`, `dataWriter()`, `posWriter()` or `targetSize()`), not in
  `AbstractRewriteFilesExecutor.execute()`.
- A new task property: add the key to `TaskProperties`, set it in
  `IcebergPartitionPlan.buildTaskProperties` (server) or `OptimizerExecutor.fillTaskProperties`
  (optimizer), and remember that the Spark optimizer image must be rebuilt.
- Core logic not to change casually: the order "position deletes first, then data rewrite", the
  reader's sequence-number rule, and the input field semantics in the table above. The commit in
  `UnKeyedTableCommit` relies on them.

## Invariants and pitfalls

- **No data loss.** A data file is removed at commit only if it is in `rewrittenDataFiles` of a
  successful task; a delete file only if it is in `rewrittenDeleteFiles` of a successful task and
  in no failed task (`UnKeyedTableCommit.getExcludedDeleteFiles`).
- **Equality deletes and sequence numbers.** New data files are committed with the data sequence
  number of the planned snapshot (`RewriteFiles.dataSequenceNumber`). Equality deletes committed
  after planning therefore still apply to the rewritten rows; `readOnlyDeleteFiles` with a
  sequence number at or below the planned snapshot no longer apply to them, which is right because
  they were applied while reading.
- **Empty output.** If every row of a task is deleted, the data writer produces no file; the commit
  still removes the input files. This is correct.
- **Resources.** Keep the try-with-resources on the reader iterators and `dataReader.close()` in
  `finally`; the equality-delete maps may spill to disk (`extend-disk-storage`).
- **Single-file output.** The `Long.MAX_VALUE` target in `IcebergRewriteExecutor.targetSize()` can
  make one output file larger than the target when deletes are few; this is intended.
- `AbstractRewriteFilesExecutor.partition()` uses the first file of `allFiles()`; a task must not
  mix partitions. The planner guarantees this because it builds tasks per partition.

## Tests

- `fusion-core/src/test/java/io/olake/fusion/optimizing/IcebergRewriteExecutorTest.java` (executor).
- `fusion-core/src/test/java/io/olake/fusion/io/TestIcebergCombinedReader.java` and
  `TestIcebergFanoutPosDeleteWriter.java` (reader and delete writer).
- End-to-end planning and commit: `fusion-ams/src/test/java/io/olake/fusion/server/optimizing/`
  (`TestIcebergHadoopOptimizing`, `commit/TestUnKeyedTableCommit`, `flow/TestUnKeyedContinuousOptimizing`).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-core -am -Dtest=IcebergRewriteExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
```

For a `fusion-ams` test, use `-pl fusion-ams -am` and the test class name.

## Related packages

- [`plan`](plan/README.md): evaluation and task planning that produce `RewriteStageTask`.
- [`evaluation`](evaluation/README.md): metadata-based shortcut for evaluation.
- [`scan`](scan/README.md): file scan that feeds the planner.
- [`../process`](../process/README.md): `StagedTaskDescriptor`, process status enums.
- [`../op`](../op/README.md): the `RewriteFiles` / `RowDelta` wrappers the commit goes through.
- [`../formats/iceberg`](../formats/iceberg/README.md): how the `MixedTable` in the input is built.
