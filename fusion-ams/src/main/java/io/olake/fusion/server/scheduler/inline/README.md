# `io.olake.fusion.server.scheduler.inline`

The server's per-table background jobs, each a `PeriodicTableScheduler`. Two of them are on the
compaction path: `TableRuntimeRefreshExecutor` is the cron tick that starts every run, and
`OptimizingCommitExecutor` commits every run. The rest do maintenance, history cleanup and health
scoring.

## Role in Fusion

```
TableRuntimeRefreshExecutor (every refresh-tables.interval, default 1 min, per table)
   refresh table -> a Lite/Medium/Full cron fired in the last 60 s?
      yes, work needed  -> markAsPending           IDLE -> PENDING
      yes, no new data  -> recordSkippedOptimization (SKIPPED row, stays IDLE)
OptimizingQueue (../../optimizing) plans and runs it    PENDING -> PLANNING -> *_OPTIMIZING -> COMMITTING
OptimizingCommitExecutor (on status change, then every 60 s while COMMITTING)
   OptimizingProcess.commit()                       COMMITTING -> IDLE
```

OLake-UI orchestrates compaction only through the cron table properties. Without a cron firing, a
table with optimization enabled is never compacted: nothing else in Fusion sets `PENDING`
(`DefaultTableRuntime.setPendingInput` can also do it, but nothing calls it).

`InlineTableExecutors.getInstance().setup(tableService, conf)` builds the executors;
`FusionServiceContainer.startOptimizingService` adds them to the handler chain in this order:
data expiring, snapshots expiring, orphan files, dangling deletes, optimizing commit, optimizing
expiring, blocker expiring, table refresh, tags, health score. Each one is added after
`DefaultOptimizingService`'s handler and `ProcessService`'s handler.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `InlineTableExecutors` | Singleton; builds executors from `FusionManagementConf` (optional ones only when enabled) and exposes getters (null when disabled). | `FusionServiceContainer` |
| `TableRuntimeRefreshExecutor` | Cron tick: refresh table metadata, evaluate Full → Major → Minor crons, mark `PENDING` or record `SKIPPED`; close the run when optimization is switched off. | `DefaultTableRuntime`, `CronUtils` (`fusion-core`) |
| `OptimizingCommitExecutor` | Runs `getOptimizingProcess().commit()` for tables in `COMMITTING`. | `OptimizingProcess` (`OptimizingQueue.TableOptimizingProcess`) |
| `OptimizingExpiringExecutor` | Deletes run history older than `runtime-data-keep-days`. | `TableProcessMapper`, `OptimizingProcessMapper` |
| `BlockerExpiringExecutor` | Deletes expired table blockers. | `TableBlockerMapper` |
| `TableHealthScoreExecutor` | Computes the health score when the snapshot changed; stores it in the table summary. | `IcebergTableUtil.createOptimizingEvaluator`, `DefaultTableRuntime.setTableSummary` |
| `SnapshotsExpiringExecutor` | `TableMaintainer.expireSnapshots()`. | `TableMaintainers` (`../../optimizing/maintainer`) |
| `OrphanFilesCleaningExecutor` | `TableMaintainer.cleanOrphanFiles()`. | same |
| `DanglingDeleteFilesCleaningExecutor` | `TableMaintainer.cleanDanglingDeleteFiles()`. | same |
| `DataExpiringExecutor` | `TableMaintainer.expireData()`. | same |
| `TagsAutoCreatingExecutor` | `TableMaintainer.autoCreateTags()` (Iceberg only). | same |

## How it works

### `TableRuntimeRefreshExecutor` (the cron tick)

- `enabled` = `instanceof DefaultTableRuntime` (always true here), so every table is ticked whether
  or not optimization is on. `getNextExecutingTime` = `interval`; `getExecutorDelay` = 0 (first tick
  10 s after start).
- `execute(tableRuntime)`:
  1. `loadTable` and `DefaultTableRuntime.refresh(table)`: store the table properties as the table
     config (this fires `handleConfigChanged` on the whole chain when they changed), the group, and
     the current snapshot ids.
  2. `evaluateCronTriggers(runtime)`:
     - return unless `cfg.isEnabled()` (`self-optimizing.enabled`) and the status is `IDLE`;
     - `snapshotChanged` = `getLastOptimizedSnapshotId() != getCurrentSnapshotId()`;
     - for `FULL`, `MAJOR`, `MINOR` in that order, take the cron (`getFullTriggerCron`,
       `getMajorTriggerCron`, `getMinorTriggerCron`); the **first** whose
       `CronUtils.hasFiredInLastMinute` is true decides, then the loop `break`s:
       - `snapshotChanged || isNecessary(candidate, lastType)` → `markAsPending(candidate)`;
       - else → `recordSkippedOptimization(candidate, buildSkipReason(...))`.
  3. Any `Throwable` is logged ("Refreshing table {} failed.").
- `isNecessary` (no new snapshot): FULL is needed when the last successful type was MINOR or MAJOR;
  MAJOR when it was MINOR; MINOR never; nothing when there was no previous run.
- `markAsPending` generates a Snowflake process id, inserts a `table_process` row (status
  `PENDING`, type `FULL`/`MAJOR`/`MINOR`, engine `"AMORO"`), and sets status `PENDING` (only from
  `IDLE`) and `process_id`. The status change reaches `DefaultOptimizingService`'s handler →
  `OptimizingQueue.refreshTable` → `SchedulingPolicy.addTable`.
- `recordSkippedOptimization` inserts and then updates a `SKIPPED` row with the reason in
  `summary.skipReason`. This is the "skipped" entry in OLake-UI's run history.
- `handleConfigChanged`: if optimization went from enabled to disabled and the run's status is
  `RUNNING` → `optimizingProcess.close(false)` (run `CLOSED`, table `IDLE`).

`CronUtils.hasFiredInWindow(expr, now, 60)` parses a 5-field UNIX cron in the server's default time
zone and is true when the last scheduled time is in `(now - 60 s, now]`. An empty or invalid
expression is `false`; OLake-UI's "Never" is an empty cron.

### `OptimizingCommitExecutor`

- `enabled` = status is `COMMITTING`. `getStartDelay()` is overridden to 0.
- `handleStatusChanged` → `scheduleIfNecessary(t, 0)` on every status change, so the commit is
  armed as soon as `beginCommitting()` commits the `COMMITTING` status.
- `execute` → `getOptimizingProcess().commit()`. A null process throws `IllegalStateException`.
  Commit errors are handled inside `commit()` (see [`../../optimizing`](../../optimizing/README.md)).
- If the table is still `COMMITTING` afterwards (Postgres write failed), the `finally` re-arm runs
  it again after `INTERVAL` = 60 s, and `commit()` takes its "already committed" path.

### `OptimizingExpiringExecutor`

One thread. Every `runtime-data-expire-interval-hours`, per table, one transaction deletes rows
older than `now - runtime-data-keep-days`, using `SnowflakeIdGenerator.getMinSnowflakeId` as the id
cutoff: `TableProcessMapper.deleteBefore` (`table_process`, `process_id <= cutoff`),
`deleteProcessStateBefore`, `deleteTaskRuntimesBefore` (`task_runtime`, `process_id < cutoff`),
`deleteOptimizingQuotaBefore`. This is what limits OLake-UI's run history.

### `BlockerExpiringExecutor`

One thread, every hour (`INTERVAL`): `TableBlockerMapper.deleteExpiredBlockers(catalog, db, table,
now)`. `OptimizingQueue.skipBlockedTables` only reads blockers that have not expired, so this is
table cleanup, not correctness.

### `TableHealthScoreExecutor`

`enabled` = `DefaultTableRuntime` and `ICEBERG`. Shares `refresh-tables.thread-count` and
`refresh-tables.interval`; `getExecutorDelay` = interval, so the first run is 10 s + one interval
after start. Per run: skip if optimization is off, if there is no snapshot, or if
`snapshotId == getHealthScoreSnapshotId()`. Otherwise build an evaluator over all partitions
(`Integer.MAX_VALUE`) with `createOptimizingEvaluator(...).getPendingInput()` and call
`setTableSummary(pendingInput, snapshotId)`. Errors are only logged as warnings. OLake-UI shows the
score in its table list.

### Maintenance executors

All load the table, call `TableMaintainers.create(amoroTable, tableRuntime)`, call one maintainer
method, and catch and log every `Throwable`. They re-arm on `handleConfigChanged`, and their
`getExecutorDelay` is a random value in `[0, interval)` to spread the load.

| Executor | `enabled` (table config) | `CleanupOperation` / restart guard |
| --- | --- | --- |
| `SnapshotsExpiringExecutor` | `isExpireSnapshotEnabled()` | `SNAPSHOTS_EXPIRING`, `now - last >= interval` |
| `OrphanFilesCleaningExecutor` | `isCleanOrphanEnabled()` | `ORPHAN_FILES_CLEANING`, same |
| `DanglingDeleteFilesCleaningExecutor` | `DefaultTableRuntime` and `isDeleteDanglingDeleteFilesEnabled()` | `DANGLING_DELETE_FILES_CLEANING`, same |
| `DataExpiringExecutor` | `getExpiringDataConfig().isEnabled()` | `DATA_EXPIRING`, same |
| `TagsAutoCreatingExecutor` | `getTagConfiguration().isAutoCreateTag()` and `ICEBERG` | none |

## State, threads and concurrency

- Each executor has its own pool, `async-<kebab-name>-%d`, sized by its thread-count key
  (`OptimizingExpiringExecutor` and `BlockerExpiringExecutor` use 1). Within one executor a table
  never runs twice at once (`scheduledTables`). Different executors do run on the same table at the
  same time, for example a snapshot expiry during a commit.
- `TableRuntimeRefreshExecutor` and `TableHealthScoreExecutor` run their own pools with the same
  sizes.
- Concurrency with compaction: the cron tick only acts on `IDLE`, and `markAsPending` only moves
  `IDLE` → `PENDING`. A tick that reads `IDLE` and a status change on another thread can still race;
  the status update itself is guarded by the runtime store's lock, but the `table_process` insert in
  `markAsPending` happens before it.
- Persisted: statuses and process rows (through `DefaultTableRuntime`), last cleanup times, health
  score and summary. The schedule itself is in memory and rebuilt on start.

## Contracts

Config keys (`FusionManagementConf`, under `ams:` in `config.yaml`); defaults in code. The sample
`dist/src/main/fusion-bin/conf/config.yaml` and `local-test/config.yaml` turn the optional jobs on.

| Key | Default | Executor |
| --- | --- | --- |
| `refresh-tables.thread-count` / `refresh-tables.interval` | 10 / 1 min | `TableRuntimeRefreshExecutor`, `TableHealthScoreExecutor` |
| `self-optimizing.commit-thread-count` | 10 | `OptimizingCommitExecutor` |
| `self-optimizing.runtime-data-keep-days` / `self-optimizing.runtime-data-expire-interval-hours` | 30 / 1 | `OptimizingExpiringExecutor` |
| `expire-snapshots.enabled` / `.thread-count` / `.interval` | false / 10 / 1 h | `SnapshotsExpiringExecutor` |
| `clean-orphan-files.enabled` / `.thread-count` / `.interval` | false / 10 / 1 d | `OrphanFilesCleaningExecutor` |
| `clean-dangling-delete-files.enabled` / `.thread-count` / `.interval` | false / 10 / 1 d | `DanglingDeleteFilesCleaningExecutor` |
| `data-expiration.enabled` / `.thread-count` / `.interval` | true / 10 / 1 d | `DataExpiringExecutor` |
| `auto-create-tags.enabled` / `.thread-count` / `.interval` | false / 3 / 1 min | `TagsAutoCreatingExecutor` |

`OptimizingCommitExecutor` (60 s), `BlockerExpiringExecutor` (1 h) and `TableHealthScoreExecutor`
have no enable flag; they are always built.

Table properties read by the cron tick (`TableProperties`, through `OptimizingConfig`):
`self-optimizing.enabled`, `self-optimizing.minor.trigger.cron` (Lite),
`self-optimizing.major.trigger.cron` (Medium), `self-optimizing.full.trigger.cron` (Full). They
have no default in Fusion; OLake-UI writes `0 * * * *` and `0 */8 * * *` for Lite and Medium when it
enables a table, and leaves Full empty.

Postgres: `table_process`, `optimizing_process_state`, `task_runtime`, `optimizing_task_quota`,
`table_blocker`, `table_runtime`, `table_runtime_state`.

## Extending or changing it

Add a new job as a `PeriodicTableScheduler` subclass here, build it in `InlineTableExecutors.setup`,
add a getter, and register it in `FusionServiceContainer.startOptimizingService`. If you add or
rename a config key, check OLake-Helm and OLake-UI's `docker-compose-v1.yml` / downloaded
`config.yaml`.

Core logic, do not change casually: `evaluateCronTriggers` (the only path into a run),
`OptimizingCommitExecutor.enabled`/`handleStatusChanged` (the only path out of `COMMITTING`), and
`OptimizingExpiringExecutor`'s cutoffs (they delete the history OLake-UI shows).

## Invariants and pitfalls

- **A cron fire can be missed.** The tick runs `interval` after the previous tick **ended**
  (fixed delay), while `hasFiredInLastMinute` uses a fixed 60 s window. With the default 1 min
  interval, consecutive ticks are more than 60 s apart (refresh time plus queueing), so a fire time
  that falls in the gap is never seen. Fires are also dropped when the table is not `IDLE` at the
  tick (for example the previous run is still going); there is no catch-up.
- **The planner checks the crons again later** with the same 60 s window, in
  `CommonPartitionEvaluator`. If planning starts more than a minute after the tick, the run can come
  out as another type, or as `SKIPPED` ("planner evaluated and found no data to process").
- **One decision per tick.** The loop stops at the first fired cron, whether it marks `PENDING` or
  records `SKIPPED`. The class Javadoc says a skipped type "falls through to the next lower-priority
  type", but the code does not. The outcome is the same, because with no new snapshot a lower type
  is never needed either; only the extra `SKIPPED` rows are not written.
- `snapshotChanged` compares against `lastOptimizedSnapshotId`, which `completeProcess` moves
  forward only on success. After a failed run the next fire runs again.
- **The tick-level skip rarely happens.** On success, `lastOptimizedSnapshotId` is set to the run's
  **target** (pre-commit) snapshot. `refresh` stores the table's current snapshot, which after a
  commit is Fusion's own `OPTIMIZE` snapshot. So the next fire sees a changed snapshot, marks
  `PENDING`, and the planner does a full evaluation before it records `SKIPPED` through
  `completeEmptyProcess`. That path does not move `lastOptimizedSnapshotId` either, so every later
  fire on an unchanged table does the same. `optimizingNotNecessary`, which would move it, has no
  callers. Check this before relying on tick-level skips to save planning work.
- `markAsPending` writes the `table_process` row before it moves the status, and does not check
  `IDLE` itself. If the status is no longer `IDLE`, the row stays `PENDING` while the table keeps its
  current process id.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/scheduler/inline/`: `TestBlockerExpiringExecutor`,
`TestConfigurableIntervalExecutors`, `TestPeriodicTableSchedulerCleanup` (bases
`ExecutorTestBase`, `PeriodicTableSchedulerTestBase`). The cron tick and commit flow are also used in
`server/TestDefaultOptimizingService` and `optimizing/TestOptimizingQueue`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestConfigurableIntervalExecutors -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md): `PeriodicTableScheduler`.
- [`../../optimizing`](../../optimizing/README.md): `OptimizingQueue`, `commit()`.
- [`../../optimizing/maintainer`](../../optimizing/maintainer/README.md): maintainers.
- `../../table`: `DefaultTableRuntime` (`markAsPending`, `recordSkippedOptimization`, `refresh`).
- `fusion-core` `io.olake.fusion.utils.CronUtils`.
