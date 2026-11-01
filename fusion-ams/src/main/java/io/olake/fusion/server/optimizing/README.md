# `io.olake.fusion.server.optimizing`

The server side of compaction. This package plans a table that is due for compaction, gives its
tasks to the optimizer, tracks every task's result, decides when a run commits or fails, and
commits the rewritten files to the Iceberg table.

## Role in Fusion

Lifecycle of one run (OLake-UI "Run" = Fusion "optimizing process"):

```
cron tick (TableRuntimeRefreshExecutor)    IDLE -> PENDING           ../scheduler/inline
optimizer thread polls, table planned      PENDING -> PLANNING       OptimizingQueue.planInternal
plan has tasks                             PLANNING -> MINOR/MAJOR/FULL_OPTIMIZING
plan is empty or fails                     PLANNING -> IDLE
all tasks SUCCESS                          *_OPTIMIZING -> COMMITTING
OptimizingCommitExecutor commits           COMMITTING -> IDLE        ../scheduler/inline
task out of retries, commit error, cancel  *_OPTIMIZING/COMMITTING -> IDLE
```

Lite = `MINOR_OPTIMIZING`, Medium = `MAJOR_OPTIMIZING`, Full = `FULL_OPTIMIZING`.

- **Who calls it.** `DefaultOptimizingService` (`fusion-ams/.../server/DefaultOptimizingService.java`)
  makes one `OptimizingQueue` per optimizer group. It serves the Thrift `OptimizingService` calls
  from the Spark optimizer (`pollTask`, `ackTask`, `completeTask`) and passes them to the queue.
  Its `TableRuntimeHandlerImpl` calls `OptimizingQueue.refreshTable` / `releaseTable` when a
  table's status or config changes. `OptimizingCommitExecutor` calls `OptimizingProcess.commit()`.
  `DefaultOptimizingService.cancelProcess` (used by `TableController`) calls `close(true)`.
- **What it calls.** `DefaultTableRuntime` (`../table`) holds the table status and moves it
  (`beginPlanning`, `beginProcess`, `beginCommitting`, `completeProcess`, `completeEmptyProcess`,
  `planFailed`). `IcebergTableUtil.createOptimizingPlanner` builds the planner
  (`fusion-core`, `io.olake.fusion.optimizing.plan`). MyBatis mappers write to Postgres.
  Iceberg's `RewriteFiles` / `RowDelta` do the commit.

Fusion has one optimizer group (`spark-container` in helm, `local` for local debugging), so in
practice there is exactly one `OptimizingQueue`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizingQueue` | Per-group queue: picks a table to plan, plans it in the background, hands out tasks, routes ack/complete, recovers runs on startup. | `SchedulingPolicy`, `DefaultTableRuntime`, `CatalogManager`, `QuotaProvider` (`DefaultOptimizingService`), `TableBlockerMapper`, `TableProcessMapper`, `OptimizingProcessMapper` |
| `OptimizingQueue.TableOptimizingProcess` (private inner) | One run. Holds its tasks, the per-run lock, the retry and commit decisions, and the Postgres writes for the run. Implements `OptimizingProcess`. | `TaskRuntime`, `UnKeyedTableCommit`, `TaskFilesPersistence`, `Telemetry` |
| `OptimizingProcess` | Interface of a run as seen outside the queue (`close`, `commit`, `getStatus`, `getTargetSnapshotId`, ...). | `DefaultTableRuntime`, `OptimizingCommitExecutor`, `DefaultTableMaintainerContext` |
| `TaskRuntime<T>` | One task (one rewrite unit) and its status machine; persists every change to `task_runtime`. | `OptimizingProcessMapper.updateTaskRuntime`, `SimpleFuture` |
| `OptimizingTaskMeta` | Plain bean of a task without input/output files (used for reads, e.g. dashboard). | — |
| `SchedulingPolicy` | Keeps the tables bound to the group and returns the next eligible (`PENDING`) table, ordered by the group's sorter. | `SorterFactory` (`./sorter`) |
| `UnKeyedTableCommit` | Builds and commits one Iceberg transaction from the SUCCESS tasks. | Iceberg `Transaction`, `RewriteFiles`, `RowDelta` |
| `OptimizingStatus` | Table status enum, persisted as a code in `table_runtime`. | `DefaultTableRuntime` |
| `OptimizerGroupMetrics` | Gauges for the group (pending/executing tasks, tables per status, optimizers, threads, memory). | `MetricRegistry` |

`OptimizingStatus` values, with `isProcessing()` and code: `FULL_OPTIMIZING` (true, 100),
`MAJOR_OPTIMIZING` (true, 200), `MINOR_OPTIMIZING` (true, 300), `COMMITTING` (true, 400),
`PLANNING` (false, 500), `PENDING` (false, 600), `IDLE` (false, 700).

## How it works

### 1. Binding tables to the queue

- `refreshTable(tableRuntime)`: if `getOptimizingConfig().isEnabled()` and the status is not
  processing, reload the task quotas of the last hour (`resetTaskQuotas`) and
  `scheduler.addTable`. It is called on table added, config changed, and on status changes to
  `IDLE` or `PENDING`.
- `releaseTable(tableRuntime)`: `scheduler.removeTable`, then `close(false)` and remove every run of
  that table from `tableQueue`.

### 2. Polling and planning (`pollTask`)

The Spark optimizer's threads call `DefaultOptimizingService.pollTask`, which calls
`OptimizingQueue.pollTask(thread, pollingTimeout, breakQuotaLimit)`:

1. `fetchScheduledTask(thread, true)`: walk `tableQueue` (runs in arrival order) and return the
   first task any run gives out under its quota.
2. If none, `waitTask(deadline)`: take `scheduleLock`, call `scheduleTableIfNecessary`, then wait on
   the `planningCompleted` condition until the deadline. Repeat step 1 after each wake-up.
3. If still none, `breakQuotaLimit` is true and no table is planning, poll once more without the
   quota check.

`scheduleTableIfNecessary` runs only when `planningTables.size() < maxPlanningParallelism`. It
builds a skip set from the tables already planning plus tables with an active `OPTIMIZE` blocker
(`skipBlockedTables` reads `TableBlockerMapper.selectAllBlockers`), asks
`SchedulingPolicy.scheduleTable(skipSet)` for one table, and calls `triggerAsyncPlanning`.

**Planning only starts when an optimizer thread polls and finds no task.** With no optimizer
running, `PENDING` tables stay `PENDING`.

`triggerAsyncPlanning` adds the table to `planningTables` and runs `planInternal` on
`planExecutor`. `planInternal`:

1. `tableRuntime.beginPlanning()` → status `PLANNING`; the `table_process` row created by the
   cron tick (`markAsPending`) is updated to `ProcessStatus.PLANNING`.
2. Load the table, `IcebergTableUtil.createOptimizingPlanner(tableRuntime.refresh(table), ...,
   getAvailableCore(), maxInputSizePerThread(), tableRuntime.getProcessId())`. The process id is the
   one `markAsPending` generated, so the run keeps the row OLake-UI already shows.
3. `planner.isNecessary()`:
   - true → `new TableOptimizingProcess(planner, tableRuntime)`, which creates a `TaskRuntime` per
     planned task (task ids 1..n) and calls `beginAndPersistProcess` (below);
   - false → `tableRuntime.completeEmptyProcess()`: row → `SKIPPED`, status → `IDLE`.
4. Any `Throwable` → `tableRuntime.planFailed(...)`: row → `FAILED`, an `ERROR` line in the driver
   log, status → `IDLE`, process id → 0. The exception is rethrown and logged by `whenComplete`.

`whenComplete` (under `scheduleLock`) sets `lastPlanTime`, removes the table from
`planningTables`, offers the new run to `tableQueue`, and `signalAll()`s `planningCompleted` so
waiting pollers retry.

`beginAndPersistProcess` runs one Postgres transaction: `TableProcessMapper.updateProcess`
(status `RUNNING`) and `updateProcessType` (the planner's type), `OptimizingProcessMapper.
insertInternalProcessState` (target snapshot, sequences), `insertTaskRuntimes` in batches of
`TASK_RUNTIME_INSERT_BATCH_SIZE` = 4000 (Postgres' 65535 bind-parameter limit),
`TaskFilesPersistence.persistTaskInputs` (into `optimizing_process_state.rewrite_input`), and
`tableRuntime.beginProcess(this)` → status `MINOR/MAJOR/FULL_OPTIMIZING` via
`OptimizingStatus.ofOptimizingType`.

The planner chooses the run's type itself (`AbstractOptimizingPlanner` picks FULL, then MAJOR, then
MINOR from its partition plans), and `CommonPartitionEvaluator` checks the crons again with
`CronUtils.hasFiredInLastMinute`. See "Invariants and pitfalls".

### 3. Handing out a task (`TableOptimizingProcess.poll`)

`lock.tryLock(10 ms)`. If the run is not `KILLED`/`FAILED` and either no quota check is asked or
`getActualQuota() < getQuotaLimit()`, take the head of `taskQueue`, increment
`optimizingTasksMap[table]`, and call `task.schedule(thread)` (status `SCHEDULED`, token, thread
id, start time, persisted). `getQuotaLimit()` is `(int) quota` when `self-optimizing.quota` > 1,
else `ceil(quota * availableCores)`, where available cores is the sum of thread counts of the
authenticated optimizers (`DefaultOptimizingService.getTotalQuota`, at least 1).

### 4. Task status machine (`TaskRuntime`)

```
PLANNED   -> SCHEDULED | CANCELED
SCHEDULED -> PLANNED | ACKED | CANCELED
ACKED     -> PLANNED | SUCCESS | FAILED | CANCELED
FAILED    -> PLANNED
SUCCESS, CANCELED: final
```

| Method | Transition | Caller |
| --- | --- | --- |
| `schedule(thread)` | → `SCHEDULED` | `poll` |
| `ack(thread)` | → `ACKED`, after `validThread` | `ackTask` (Thrift) |
| `complete(thread, result)` | → `FAILED` (error message set) or `SUCCESS` (output stored); `runTimes += 1`; then `future.complete()` | `completeTask` (Thrift) |
| `reset()` | → `PLANNED`, clears token/thread/times; keeps `costTime` | `resetTask` (retry) |
| `tryCanceling()` | → `CANCELED` if allowed | `persistAndSetCompleted(false)` |

Each method runs in `invokeConsistency` (`StatedPersistentBase`): one Postgres transaction, and the
`@StateField` fields are restored if anything throws. `validThread` rejects a result whose token or
thread id does not match the task's current owner, and any result for a task that was reset.

### 5. Accepting a result (`acceptResult`)

Registered as the `SimpleFuture` callback of each task, so it runs on the Thrift thread inside
`complete()`'s transaction. Under the run's `lock`:

1. Decrement `optimizingTasksMap[table]`; `tableRuntime.addTaskQuota(getCurrentQuota())`
   (failure ignored).
2. `CANCELED` → return. Run `isClosed()` (status `KILLED`) → throw `OptimizingClosedException`.
3. `SUCCESS` and `allTasksPrepared()` (every task `SUCCESS`) and the table is processing but not
   `COMMITTING` → `tableRuntime.beginCommitting()`.
4. `FAILED`:
   - `getRetry()` (= `runTimes - 1`) < `self-optimizing.execute.num-retries` → `retryTask` →
     `resetTask`: `reset()` and back on `taskQueue`;
   - else, if `self-optimizing.allow-partial-commit` → `beginCommitting()` (commit the SUCCESS tasks);
   - else run `FAILED`, `persistAndSetCompleted(false)`.

With the default `num-retries = 0`, the first failure of any task fails the run.

### 6. Committing (`TableOptimizingProcess.commit`)

Called by `OptimizingCommitExecutor` on its own thread while the table is `COMMITTING`. Under
`lock`:

1. If `hasCommitted` is already true: `persistAndSetCompleted(status == SUCCESS)`, then throw
   `IllegalStateException("repeat commit ...")`. This is the path that finishes a run whose Iceberg
   commit worked but whose Postgres write failed.
2. Set `hasCommitted = true`; `buildCommit().commit()`. `buildCommit` reloads the table and
   requires `isUnkeyedTable()` ("Fusion commits Iceberg tables only").
3. Run status: all tasks `SUCCESS` → `SUCCESS`; any `FAILED` → `FAILED`; otherwise `CLOSED`.
   Then `persistAndSetCompleted(status == SUCCESS)`.
4. `PersistenceException` → logged, status stays `COMMITTING`; the next commit tick goes to step 1.
5. Any other `Throwable` (including `OptimizingCommitException`) → `FAILED`,
   `persistAndSetCompleted(false)`.

`UnKeyedTableCommit.commit()` collects, from SUCCESS tasks only: added data/delete files from the
output; removed data files and removed delete files from the input (`rewrittenDataFiles`,
`rewrittenDeleteFiles`). A delete file that a non-SUCCESS task also rewrites is not removed
(`getExcludedDeleteFiles`). In one Iceberg `Transaction`:

- normally one `RewriteFiles` with all four sets;
- if no delete file is removed but some are added: `RewriteFiles` for the data files, then a
  `RowDelta` that adds the delete files (avoids `BaseRewriteFiles#validateReplacedAndAddedFiles`).

When `targetSnapshotId` is valid, the rewrite uses `validateFromSnapshot(targetSnapshotId)` and
`dataSequenceNumber(<target snapshot's sequence number>)`. Both operations set
`snapshot-producer = OPTIMIZE` in the snapshot summary and scan manifests with
`IcebergThreadPools.getCommitExecutor()`. Any exception is wrapped in `OptimizingCommitException`.

### 7. Finishing a run (`persistAndSetCompleted(success)`)

One transaction: when not success, `cancelTasks()` (`tryCanceling` on every task);
`TableProcessMapper.updateProcess` (final status, finish time, fail reason, summary);
`tableRuntime.completeProcess(success)` → status `IDLE`; `clearProcess` (remove from `tableQueue`).
After it, when it failed with a reason, `appendDriverLogEntry(processId, "ERROR", ...)`.
`completeProcess` moves `lastOptimizedSnapshotId` and `lastOptimizingType` forward only on
success, and always sets the last Minor/Major/Full time to the plan time. `trackCompleted()` sends
telemetry (`SUCCESS`, `CANCELLED` for `CLOSED`, else `FAILED`).

### 8. Closing a run (`close(needCommit)`)

Only acts when the run is `RUNNING`. With `allow-partial-commit` and `needCommit` →
`beginCommitting()`. Otherwise run `CLOSED`, `persistAndSetCompleted(false)`. Callers:
`close(true)` from `cancelProcess` (OLake-UI cancel); `close(false)` from `releaseTable`,
`TableRuntimeRefreshExecutor.handleConfigChanged` (optimization switched off),
`DefaultTableRuntime.refresh` (group changed), `DefaultTableRuntime.dispose`, and startup recovery.

### 9. Status transitions and the code that makes them

| From → To | Code |
| --- | --- |
| `IDLE` → `PENDING` | `DefaultTableRuntime.markAsPending` (from `TableRuntimeRefreshExecutor`) |
| `PENDING` → `PLANNING` | `planInternal` → `beginPlanning` |
| `PLANNING` → `*_OPTIMIZING` | `beginAndPersistProcess` → `beginProcess` |
| `PLANNING` → `IDLE` (SKIPPED) | `planInternal` → `completeEmptyProcess` |
| `PLANNING` → `IDLE` (FAILED) | `planInternal` catch → `planFailed`; also `initTableRuntime` on startup |
| `*_OPTIMIZING` → `COMMITTING` | `acceptResult` or `close(true)` → `beginCommitting` |
| `*_OPTIMIZING`/`COMMITTING` → `IDLE` | `persistAndSetCompleted` → `completeProcess` (from `commit`, `acceptResult`, `close`) |

## State, threads and concurrency

| Thread | Runs |
| --- | --- |
| Thrift optimizing-service workers | `pollTask`, `ackTask`, `completeTask`; so also `acceptResult` and `beginCommitting` |
| `plan-executor-thread-%d` (cached pool in `DefaultOptimizingService`, daemon) | `planInternal` |
| `async-optimizing-commit-executor-%d` | `commit()` |
| `optimizer-keeper-thread` (`DefaultOptimizingService.OptimizerKeeper`) | `retryTask` for tasks of expired optimizers or ack/execute timeouts |

Locks:

- `scheduleLock` (`ReentrantLock`) + `planningCompleted` (`Condition`): guards choosing a table and
  `planningTables` updates; pollers wait on the condition. `planningTables` is a plain `HashSet`;
  `pollTask` reads `isEmpty()` without the lock (a stale read only changes whether the quota-free
  poll happens).
- `TableOptimizingProcess.lock`: one per run; `poll` uses `tryLock(10 ms)` and skips a busy run.
  `ackTask`, `completeTask`, `acceptResult`, `resetTask`, `close`, `commit` take it.
- `SchedulingPolicy.tableLock`: guards its table map.
- `optimizingTasksMap` (`ConcurrentHashMap<ServerTableIdentifier, AtomicInteger>`): tasks handed
  out per table, for the quota check. In memory only.

In Postgres: table status and process id (`table_runtime`, `table_runtime_state`), runs
(`table_process`), target snapshot, sequences and task inputs (`optimizing_process_state`), tasks
(`task_runtime`), task quotas (`optimizing_task_quota`). In memory only: `tableQueue`,
`taskQueue`, `planningTables`, `optimizingTasksMap`, `hasCommitted`.

### Restart (`initTableRuntime`, called from the `OptimizingQueue` constructor)

- `PLANNING` → `planFailed("orphaned PLANNING on AMS startup ...")` → `IDLE`.
- Processing with a process id → rebuild the run from `getProcessMeta`, `getProcessState`,
  `selectTaskRuntimes`, `loadTaskInputs`. `PLANNED` tasks go back on `taskQueue`, `FAILED` tasks are
  reset and queued. `SCHEDULED`/`ACKED` tasks wait for `OptimizerKeeper`: optimizers are reloaded
  from Postgres, so a Spark optimizer that survived the restart can still ack and complete them;
  otherwise they are reset after `optimizer.heart-beat-timeout`. If the inputs cannot be loaded
  (`IllegalArgumentException`), the run is closed.
- `COMMITTING` → `close(false)`: the run is recorded `CLOSED` and its output files are left
  unreferenced. This avoids committing twice.
- Optimization disabled → the run is closed.
- Not processing and enabled → `scheduler.addTable`.

## Contracts

- **Thrift** (`OptimizingService.Iface`, via `DefaultOptimizingService`): `pollTask` →
  `OptimizingQueue.pollTask`; `ackTask` → `ackTask`; `completeTask` → `completeTask`;
  `cancelProcess` → `close(true)`. Unknown task or run → `TaskNotFoundException`; wrong state →
  `IllegalTaskStateException`; wrong thread → `TaskRuntimeException`.
- **Mappers**: `TableProcessMapper` (`table_process`), `OptimizingProcessMapper`
  (`optimizing_process_state`, `task_runtime`, `optimizing_task_quota`), `TableBlockerMapper`
  (`table_blocker`).
- **Server config** (`FusionManagementConf`, under `ams:` in `config.yaml`):

| Key | Default | Used for |
| --- | --- | --- |
| `optimizer.max-planning-parallelism` | 1 | `maxPlanningParallelism` |
| `optimizer.polling-timeout` | 3 s | `pollTask` wait |
| `self-optimizing.break-quota-limit-enabled` | true | quota-free poll |
| `optimizer.heart-beat-timeout` | 1 min | `OptimizerKeeper` expiry |
| `optimizer.task-ack-timeout` | 30 s | `SCHEDULED` tasks reset |
| `optimizer.task-execute-timeout` | `Integer.MAX_VALUE` s (sample configs: 1 h) | `ACKED` tasks reset |

- **Optimizer-group property**: `scheduling-policy` (default `quota`, see `./sorter`);
  `max-input-file-size-per-thread` (`OptimizerProperties`, default 512 MB).
- **Table properties** (`TableProperties`, parsed by `TableConfigurations`):
  `self-optimizing.enabled` (default true; OLake-UI's catalog default is false),
  `self-optimizing.quota` (0.5; OLake-UI sets 0.1 on the catalog),
  `self-optimizing.execute.num-retries` (0), `self-optimizing.allow-partial-commit` (false),
  `self-optimizing.group` (`default`).
- **Metrics** (`OptimizerGroupMetrics`, tag `group`): `optimizer_group_pending_tasks`,
  `optimizer_group_executing_tasks`, `optimizer_group_planing_tables`,
  `optimizer_group_pending_tables`, `optimizer_group_executing_tables`,
  `optimizer_group_idle_tables`, `optimizer_group_committing_tables`,
  `optimizer_group_optimizer_instances`, `optimizer_group_memory_bytes_allocated`,
  `optimizer_group_threads`.
- **Driver log**: `DefaultTableRuntime.appendDriverLogEntry` writes JSON lines to
  `$LOG_DIR/<processId>/driver.log` (default `/mnt/amoro-logs/compaction`).

## Extending or changing it

- A new table ordering: implement `SorterFactory` (`./sorter`).
- A reaction to status/config changes: add a `RuntimeHandlerChain` handler (`../table`), not code
  here.
- Core logic, change only with care and tests: the `TaskRuntime` status map, `acceptResult`,
  `commit`, `persistAndSetCompleted`, `initTableRuntime`, `UnKeyedTableCommit`. A change in the
  order of the transaction steps changes what survives a crash.

## Invariants and pitfalls

- **One run per table.** `SchedulingPolicy` only picks `PENDING` tables, never processing or
  `PLANNING` ones; `planningTables` stops a second plan of the same table.
- **Every exit returns to `IDLE`.** Plan error → `planFailed`; empty plan →
  `completeEmptyProcess`; run end → `persistAndSetCompleted` → `completeProcess`. If you add an exit
  path, it must go through one of these.
- **Late or duplicate results are rejected.** After a reset the token is null, after a reschedule
  it is another thread's, so `validThread` throws; after a run finishes it is gone from
  `tableQueue`, so `findProcess` throws `TaskNotFoundException`.
- **Commit conflicts with OLake writes.** OLake keeps writing while a run is in flight.
  `validateFromSnapshot` makes Iceberg reject the rewrite if a rewritten file was changed or deleted
  after the target snapshot; the run then becomes `FAILED` and the table `IDLE`, and the next cron
  fire plans again. The new files get the target snapshot's data sequence number, so equality
  deletes OLake commits after planning still apply to the rewritten rows. If the target snapshot is
  expired before the commit, `snapshot(targetSnapshotId)` returns null and the commit fails.
- **Planning may not run the type the cron asked for.** The cron tick records the type in
  `table_process`, but the planner picks its own type and checks the crons again with a 60 s
  window. If planning starts more than 60 s after the cron fired (no optimizer polling, or another
  table planning), a Medium or Full fire can plan as Minor or as an empty (`SKIPPED`) run.
  `updateProcessType` overwrites the recorded type with the planner's.
- **Quota counter can drift up.** `optimizingTasksMap` goes up in `poll` and down only in
  `acceptResult`. A task that `OptimizerKeeper` resets (optimizer expired, ack or execute timeout)
  never reaches `acceptResult`, and the map is never cleared. After an optimizer pod restart the
  table can look over quota until the server restarts. It is still served by the quota-free poll
  while `break-quota-limit-enabled` is true and no table is planning.
- `isClosed()` is true only for `KILLED`, and nothing in Fusion sets `KILLED` now.
- `initTableRuntime` calls `tableRuntime.recover(process)` after the recovery constructor already
  did. It is harmless for a `RUNNING` run. If the stored run were `SUCCESS` with the table still
  processing, the second call would throw. The final write is one transaction, so that state should
  not happen.
- `buildCommit` throws `IllegalStateException` for a table that is not unkeyed; Fusion commits
  Iceberg tables only.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/optimizing/`: `TestOptimizingQueue`,
`OptimizingStatusTest`, `TestIcebergHadoopOptimizing`, `commit/TestUnKeyedTableCommit`,
`flow/TestUnKeyedContinuousOptimizing`, `plan/*`, `scan/*`. Also
`server/TestDefaultOptimizingService`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestOptimizingQueue -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`./maintainer`](maintainer/README.md): table maintenance context.
- [`./sorter`](sorter/README.md): table ordering for `SchedulingPolicy`.
- [`../scheduler/inline`](../scheduler/inline/README.md): cron tick and commit executor.
- [`../scheduler`](../scheduler/README.md): `PeriodicTableScheduler`.
- [`../process`](../process/README.md): `TableProcessMeta`, generic process framework.
- `../table`: `DefaultTableRuntime`, `RuntimeHandlerChain`.
- `fusion-core` `io.olake.fusion.optimizing.plan`: planners and evaluators.
