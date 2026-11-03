# `io.olake.fusion.optimizing.plan`

Decides, per partition, whether a table needs Minor, Major or Full compaction, and splits the chosen
files into rewrite tasks (`RewriteStageTask`). This is the core of "what to compact".

## Role in Fusion

**Server side only** (`fusion-ams`). Nothing here runs in the optimizer.

Two entry points, both created by `io.olake.fusion.server.utils.IcebergTableUtil` in `fusion-ams`:

| Entry point | Created by | Used for |
| --- | --- | --- |
| `IcebergOptimizingPlanner` | `IcebergTableUtil.createOptimizingPlanner`, called from `OptimizingQueue.planInternal` | Planning a process after a cron marked the table `PENDING` |
| `IcebergOptimizerEvaluator` | `IcebergTableUtil.createOptimizingEvaluator`, called from `TableHealthScoreExecutor` | Health score and table summary (`getPendingInput()`), no tasks |

Lifecycle position:

1. `TableRuntimeRefreshExecutor.evaluateCronTriggers` (fusion-ams) checks the crons in the order
   FULL, MAJOR, MINOR with `CronUtils.hasFiredInLastMinute`. The first fired cron either marks the
   table `PENDING` (`markAsPending`) or records a `SKIPPED` process when the snapshot is unchanged
   since the last optimization.
2. `OptimizingQueue.planInternal` builds the planner and calls `isNecessary()`. If false, the table
   gets `completeEmptyProcess()`; if true, a `TableOptimizingProcess` is created from
   `planTasks()`, `getOptimizingType()`, `getTargetSnapshotId()`.
3. The type of the cron that fired is **not passed** to the planner. The planner re-derives the
   type from the crons again (see "Pitfalls").

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `AbstractOptimizingEvaluator` | Scans the snapshot, builds one `PartitionEvaluator` per partition path, keeps those that `isNecessary()` (capped by `maxPendingPartitions`). Builds `PendingInput` (counts, sizes, health score). | `TableFileScanHelper`, `PartitionEvaluator` |
| `IcebergOptimizerEvaluator` | Evaluator that builds a `CommonPartitionEvaluator` per partition. | `CommonPartitionEvaluator` |
| `AbstractOptimizingPlanner` | Evaluator subclass (`maxPendingPartitions = Integer.MAX_VALUE`) that turns necessary partitions into tasks and picks the process type. | `AbstractPartitionPlan`, `RewriteStageTask` |
| `IcebergOptimizingPlanner` | Planner that builds an `IcebergPartitionPlan` per partition. | `IcebergPartitionPlan` |
| `PartitionEvaluator` | Interface: `addFile`, `isNecessary`, `getOptimizingType`, `getCost`, `getWeight`, `getHealthScore`, file statistics. | |
| `CommonPartitionEvaluator` | All decision rules: file classification, delete accounting, Minor/Major/Full necessity, cost, health score. | `OptimizingConfig`, `CronUtils`, `MetadataBasedEvaluationEvent` |
| `AbstractPartitionPlan` | Per-partition file buckets (`rewriteDataFiles`, `undersizedSegmentFiles`, `rewritePosDataFiles`, `reservedDeleteFiles`), bin-packing (`BinPackingTaskSplitter`), task building (`SplitTask.buildTask`). Delegates decisions to its own `CommonPartitionEvaluator`. | `CommonPartitionEvaluator`, `RewriteFilesInput` |
| `IcebergPartitionPlan` | Iceberg specifics: `BinPackingTaskSplitter`, task property `task-executor-factory-impl = IcebergRewriteExecutorFactory`, drops tasks that would only copy one file (`enoughInputFiles`). | `IcebergRewriteExecutorFactory` |

## How it works

### Thresholds (from `OptimizingConfig`, parsed in `TableConfigurations.parseOptimizingConfig`)

| Property | Default | Use |
| --- | --- | --- |
| `self-optimizing.enabled` | `true` (OLake-UI catalogs set `false`) | `CommonPartitionEvaluator.addFile` returns `false` for every file when disabled |
| `self-optimizing.target-size` | `134217728` (128 MB; OLake-UI 512 MB) | `targetSize` |
| `self-optimizing.fragment-ratio` | `8` | `fragmentSize = targetSize / fragmentRatio` (16 MB at defaults) |
| `self-optimizing.min-target-size-ratio` | `0.75` | `minTargetSize = targetSize * ratio` (96 MB at defaults) |
| `self-optimizing.minor.trigger.file-count` | `12` | Minor file-count trigger, health score |
| `self-optimizing.major.trigger.duplicate-ratio` | `0.1` | Position-delete ratio that forces a segment rewrite; health score |
| `self-optimizing.full.rewrite-all-files` | `true` | In Full, rewrite every data file |
| `self-optimizing.max-task-size-bytes` | `134217728` | Bin capacity is `max(targetSize, maxTaskSize)` |
| `read.split.open-file-cost` (`SPLIT_OPEN_FILE_COST`) | 4 MB | Per-file cost in `getCost()` |
| `self-optimizing.minor.trigger.cron` / `major` / `full` | `null` (never) | Cron windows |
| `self-optimizing.filter` | `null` | SQL filter turned into an Iceberg expression (`getPartitionFilter`) |
| `self-optimizing.evaluation.fallback-interval` | `-1` (off) | Metadata-based gate, see [`evaluation`](../evaluation/README.md) |
| `self-optimizing.evaluation.file-size.mse-tolerance` | `0` | Metadata-based gate |

### Step 1: scan and group (`AbstractOptimizingEvaluator.initEvaluator`)

- `IcebergTableFileScanHelper(table, currentSnapshot.snapshotId())` with the partition filter runs
  `table.newScan().useSnapshot(id).filter(filter).planFiles()`. Each result is one data file plus
  the delete files Iceberg says apply to it (`FileScanTask.deletes()`).
- Files are grouped by `partitionSpec.partitionToPath(partition)`; the evaluator for a path is
  created on the first file with `buildEvaluator(Pair.of(specId, partition))`.
- `needOptimizingPlanMap` = partitions with `isNecessary()`, limited to `maxPendingPartitions`.
- The planner's filter: `getPartitionFilter()` uses the filter passed in, but if that is
  `alwaysTrue` and `self-optimizing.filter` is set, uses the configured filter.

### Step 2: classify each data file (`CommonPartitionEvaluator.addFile`)

| Class | Size rule |
| --- | --- |
| Fragment (`isFragmentFile`) | `size <= fragmentSize` |
| Undersized segment (`isUndersizedSegmentFile`) | `fragmentSize < size <= minTargetSize` |
| Target-size-reached | `size > minTargetSize` |

Rules used by both evaluator and plan:

- `fileShouldRewrite(file, deletes)`:
  - Full window active (`isFullOptimizing()` = full cron fired in the last 60 s, measured when the
    evaluator was constructed): `fileShouldFullOptimizing` → `true` for **every** file when
    `self-optimizing.full.rewrite-all-files` is `true`; otherwise `true` if the file has any delete,
    or is a fragment or undersized segment.
  - Otherwise: fragments always; any other file when its **position-delete record count** is
    greater than `recordCount * major.trigger.duplicate-ratio`. Equality deletes are deliberately
    ignored here (they become position deletes first).
- `segmentShouldRewritePos(file, deletes)` (non-fragment only): `true` if the file has more than one
  position delete (and increments `combinePosSegmentFileCount`), or any equality delete, or exactly
  one position delete that was **not** written by the optimizer for this file
  (`TableFileUtil.isOptimizingPosDeleteFile`, name prefix `<datafile>-delete`).

What happens per class (evaluator counters, then plan bucket in `AbstractPartitionPlan.addFile`):

| Class | Evaluator | Plan bucket |
| --- | --- | --- |
| Fragment | Counted in `fragmentFile*`, its deletes counted | `rewriteDataFiles` |
| Undersized segment | Deletes counted; `rewriteSegmentFile*` if `fileShouldRewrite`, else `undersizedSegmentFile*` (and the two smallest sizes are cached) | `rewriteDataFiles` or `undersizedSegmentFiles` |
| Target-size-reached | `rewriteSegmentFile*` if `fileShouldRewrite`; else `rewritePosSegmentFile*` if `segmentShouldRewritePos`; else **not added** | `rewriteDataFiles`, `rewritePosDataFiles`, or not added |

If a file is not added, all its delete paths go to `reservedDeleteFiles`. Deletes are counted once
per path (`isDuplicateDelete`).

### Step 3: necessity and type (`CommonPartitionEvaluator.isNecessary`, cached)

1. Metadata-based gate (only if `evaluation.fallback-interval >= 0` and the fallback interval has
   not passed since the latest of the three last-optimizing times): the partition is not necessary
   unless `MetadataBasedEvaluationEvent.isPartitionPendingNecessary` holds.
2. `necessary = isFullNecessary() || isMajorNecessary() || isMinorNecessary()`:

| Type | Condition |
| --- | --- |
| Full (`isFullNecessary`) | Full cron fired in the last 60 s **and** (any delete, or `fragmentFileCount >= 2`, or `undersizedSegmentFileCount >= 2`, or `rewriteSegmentFileCount > 0`, or `rewritePosSegmentFileCount > 0`) |
| Major (`isMajorNecessary` → `isMajorIntervalNecessary`) | Major cron fired in the last 60 s **and** (`fragmentFileCount + getSegmentFileCount() > 1`, or any delete) |
| Minor (`isMinorNecessary`) | `fragmentFileCount + equalityDeleteFileCount >= minor.trigger.file-count`, **or** (that sum `> 0` and the Minor cron fired in the last 60 s), **or** `combinePosSegmentFileCount > 0` |

`getOptimizingType()` returns FULL if Full is necessary, else MAJOR if Major is necessary, else MINOR.
The process type (`AbstractOptimizingPlanner.planTasks`) is FULL if any planned partition is FULL,
else MAJOR if any is MAJOR, else MINOR.

Important: Minor and Major select files the **same way**. `fileShouldRewrite` only looks at the
Full window, and undersized segments are bin-packed in every type. Major differs only in its trigger
and in the label. `enoughContent()` exists but is not called.

### Step 4: split into tasks (`AbstractOptimizingPlanner.planTasks`, `BinPackingTaskSplitter`)

1. Necessary partitions are sorted by `getWeight()` (cost) descending. `maxInputSize` is
   `Double.MAX_VALUE`, so every necessary partition is planned in one pass; `targetTaskCount` is
   computed but ignored by the bin-packing splitter.
2. Per partition, `splitTasks`:
   1. Bin-pack `undersizedSegmentFiles` with `BinPacking.ListPacker(max(targetSize, maxTaskSize),
      Integer.MAX_VALUE, false)` by file size. Bins with more than one file become rewrite tasks.
      A single-file bin goes to `disposeUndersizedSegmentFile`: moved to `rewritePosDataFiles` if
      `segmentShouldRewritePos`, else its deletes are reserved.
   2. Bin-pack `rewriteDataFiles` (rewrite) and `rewritePosDataFiles` (position-delete conversion
      only) together with the same packer.
   3. `IcebergPartitionPlan.filterSplitTasks` drops a task that has exactly one rewrite data file, no
      `rePos` file and no delete file (it would only copy the file).
3. `SplitTask.buildTask` puts every delete whose path is in `reservedDeleteFiles` into
   `readOnlyDeleteFiles`, the rest into `rewrittenDeleteFiles`, and builds
   `new RewriteStageTask(tableId, partitionPath, input, properties)`. Tasks are built after all
   splitting, so the reserved set is complete.
4. `planTasks()` is cached; `isNecessary()` on the planner is `super.isNecessary() && !planTasks().isEmpty()`.

### Cost and health score

- `getCost()`: `(fragment + rewriteSegment + undersizedSegment sizes) * 2 + rewritePosSegmentSize / 10
  + posDeleteSize + eqDeleteSize + fileCount * openFileCost`. Only used for ordering (a TODO says it
  is no longer needed).
- `getHealthScore()`: small-file score `ceil(40 - penalty * 40 * (1 - avgSize/minTargetSize))`,
  equality score `ceil(40 - penalty * 40 * min(eqRatio, dupRatio)/dupRatio)`, position score with
  max 20 and threshold `2 * dupRatio`. `penalty` is 0 for at most one file, else
  `min(files, minorFileCount)/minorFileCount * min(size, minTargetSize)/minTargetSize`.
  `PendingInput` averages per partition (defaults 40/40/20 when no partition) and sums the three.
- `PendingInput(evaluators, snapshot)` takes totals from the snapshot summary
  (`total-data-files`, `total-delete-files`, `total-records`, `total-files-size`) and derives
  `danglingDeleteFileCount = totalDeleteFiles - eqCount - posCount`.

## State, threads and concurrency

- Evaluators and plans are single-use and single-threaded; the planner runs on the
  `OptimizingQueue` planning thread. Results (`necessary`, `optimizingType`, `cost`, `tasks`) are
  cached in plain fields.
- The Iceberg scan itself runs on `IcebergThreadPools.getPlanningExecutor()`.
- Cron windows are evaluated with `System.currentTimeMillis()` in the JVM's default time zone
  (`CronUtils.hasFiredInWindow`).

## Contracts

- Output: `List<RewriteStageTask>` whose `RewriteFilesInput` follows the field semantics in
  [`optimizing`](../README.md#input-layout-built-on-the-server); the commit relies on
  `readOnlyDeleteFiles` vs `rewrittenDeleteFiles` being correct.
- `getTargetSnapshotId()` is the snapshot the plan read; the commit validates from it and uses its
  sequence number as the data sequence number of new files.
- `getFromSequence()` / `getToSequence()` return per-partition sequences only when `markSequence`
  was called; nothing in the Iceberg path calls it, so both maps are empty.
- `PendingInput` is persisted as JSON in the table runtime state (`PENDING_INPUT_KEY`); keep its
  fields backward compatible. `partitions` is `@JsonIgnore`.
- Iceberg APIs (1.7.2): `TableScan.useSnapshot/filter/planFiles/planWith`, `FileScanTask.deletes()`,
  `PartitionSpec.partitionToPath`, `BinPacking.ListPacker`, `SnapshotSummary` keys.

## Extending or changing it

- Decision rules belong in `CommonPartitionEvaluator`; Iceberg task shaping in `IcebergPartitionPlan`
  (`buildTaskSplitter`, `buildTaskProperties`, `filterSplitTasks`).
- Do not change casually: `reservedDeleteFiles` handling, the delete-file split in `buildTask`, the
  bucket order in `AbstractPartitionPlan.addFile` (it must mirror the evaluator), and the size
  classes. These decide which files the commit removes.
- If the type of the cron that fired should drive planning, it has to be passed from
  `DefaultTableRuntime.pendingCronType` into the planner; today it is not.

## Invariants and pitfalls

- **A delete file may be removed only if every data file it applies to was handled in this plan.**
  This is enforced only through `reservedDeleteFiles`, which is per partition and only sees files
  the scan returned. Two cases are not protected, to my reading of the code:
  - `self-optimizing.filter` on a non-partition column: the scan prunes data files by column
    metrics, so a data file the filter skips never reserves its deletes, and the commit may remove
    a delete that still applies to it.
  - Global equality deletes (unpartitioned spec after partition evolution) apply across
    partitions, but reservation is per partition.
  OLake-UI sets neither, but check these before relying on them.
- **The cron is re-checked at plan time.** `CommonPartitionEvaluator` computes
  `reachMajorInterval` / `reachFullInterval` with a 60-second window when it is constructed, and
  `reachMinorInterval()` when called. If planning starts more than 60 s after the cron fired (queue
  busy, slow scan), Major/Full are not recognised; the partition can still be planned as Minor
  through the file-count rule, or the process ends empty.
- **Minor without a Minor cron.** `isMinorNecessary` is true at 12+ small files even if only the
  Major or Full cron fired, so a Major cron with too little data still produces a Minor process.
- **Full rewrites everything by default** (`full.rewrite-all-files=true`): every data file in a
  necessary partition is rewritten, including well-sized files without deletes.
- Partitions are keyed by partition **path**; files from two specs that render to the same path
  share one evaluator, which keeps the spec id of the first file.
- `CloseableIterable` from `scan()` is closed by try-with-resources in `initPartitionPlans`.
- `AbstractPartitionPlan.getHealthScore()` reads the `evaluator` field directly (not `evaluator()`);
  it is safe only because a plan exists only after its first `addFile`.

## Tests

In `fusion-ams/src/test/java/io/olake/fusion/server/optimizing/plan/`: `TestOptimizingPlanner`,
`TestOptimizingEvaluator`, `TestIcebergPartitionPlan`, `TestUnkeyedPartitionPlan`
(base class `MixedTablePlanTestBase`).

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestIcebergPartitionPlan -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md): task input/output and the rewrite executor.
- [`../evaluation`](../evaluation/README.md): metadata-based gate.
- [`../scan`](../scan/README.md): `TableFileScanHelper`.
- [`../../process`](../../process/README.md): `StagedTaskDescriptor` behind `RewriteStageTask`.
