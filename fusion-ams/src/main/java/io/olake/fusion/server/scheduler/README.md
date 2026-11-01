# `io.olake.fusion.server.scheduler`

`PeriodicTableScheduler`, the base class for every per-table background job in the server: the
cron tick, the commit executor, cleanup jobs and the health score. It is also a
`RuntimeHandlerChain`, so it hears about table events.

## Role in Fusion

Every class in [`./inline`](inline/README.md) extends it, and so does
`process.ActionCoordinatorScheduler`. The most important subclasses are:

- `TableRuntimeRefreshExecutor`: the one-minute cron tick that moves `IDLE` → `PENDING`.
- `OptimizingCommitExecutor`: commits a run while the table is `COMMITTING`.

`InlineTableExecutors.setup` builds the instances. `FusionServiceContainer.startOptimizingService`
adds each one to the table service's handler chain (`addHandlerChain`, which skips null, i.e. jobs
switched off). `DefaultTableService.initialize` then calls `RuntimeHandlerChain.initialize`, which
reaches `initHandler` here.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `PeriodicTableScheduler` (abstract) | Schedules each table on its own `ScheduledExecutorService`, at most once at a time per table; re-arms after every run; records the last cleanup time for cleanup jobs. | `TableService`, `DefaultTableRuntime`, `CleanupOperation` (`../table/cleanup`), `Action` |

Subclasses must implement `getNextExecutingTime(tableRuntime)` (delay in ms until the next run),
`enabled(tableRuntime)`, `execute(tableRuntime)` and `getExecutorDelay()`. They may override
`getCleanupOperation()` (default `NONE`), `shouldExecute(lastCleanupEndTime)` (default true),
`getStartDelay()`, `getThreadName()`, and the `handle*` event methods.

## How it works

### Start (`initHandler`)

For each table where `enabled(tableRuntime)` is true and that is not yet in `scheduledTables`,
schedule `executeTask` after `calculateExecutionDelay`:

- `shouldExecuteTask(...)` true → `getStartDelay()` = `START_DELAY` (10 s) + `getExecutorDelay()`;
- otherwise `getNextExecutingTime(...) + getStartDelay()`.

`shouldExecuteTask` is true when the job has no cleanup operation (or the runtime is not a
`DefaultTableRuntime`), when `getLastCleanTime(op)` is 0, or when `shouldExecute(lastCleanTime)`
says enough time has passed. This makes interval cleanup jobs keep their pace across server
restarts.

### One run (`executeTask`)

```
try   { if (isExecutable) { execute(t); persistUpdatingCleanupTime(t); } }
finally { scheduledTables.remove(id); scheduleIfNecessary(t, getNextExecutingTime(t)); }
```

- `isExecutable` = `tableService.contains(tableId) && enabled(tableRuntime)`.
- `persistUpdatingCleanupTime` → `DefaultTableRuntime.updateLastCleanTime(op, now)` (state key
  `cleanup_state` in `table_runtime_state`); skipped for `NONE`; errors are logged.
- `scheduleIfNecessary(t, delay)` re-arms only if the table is still executable and could be added
  to `scheduledTables`. A table that is disabled or removed drops out here and is not scheduled
  again until an event calls `scheduleIfNecessary`.

The next run is scheduled `getNextExecutingTime` ms **after the current run ends**, a fixed delay
and not a fixed rate. The real period is the interval plus the run time plus any wait for a free
thread.

### Events

| Event | Default here | Overridden by |
| --- | --- | --- |
| `handleTableAdded` | `scheduleIfNecessary(t, getStartDelay())` | — |
| `handleConfigChanged` | nothing | cleanup/tag executors (re-arm), `TableRuntimeRefreshExecutor` (close run when optimization is switched off) |
| `handleStatusChanged` | nothing | `OptimizingCommitExecutor` (re-arm with delay 0) |
| `handleTableRemoved` | nothing; the next `executeTask` sees `contains == false` | — |

`RuntimeHandlerChain` delivers events only after `initialize`, only for formats where
`formatSupported` is true (default: `ICEBERG` only), and catches and
logs every exception (`doSilently`).

### Shutdown (`doDispose` → `gracefulShutdown`)

`shutdown()`, wait 30 s, then `shutdownNow()` and wait 30 s more; logs an error if the pool still
does not stop.

## State, threads and concurrency

- One `Executors.newScheduledThreadPool(poolSize)` per subclass instance, non-daemon threads named
  `async-<kebab-case class name>-%d` (for example `async-table-runtime-refresh-executor-0`).
- `scheduledTables`: `Collections.synchronizedSet`, in memory. It guarantees at most one pending
  or running job per table per scheduler, so one table's jobs never overlap within one executor.
  Different executors do run at the same time on the same table.
- Persisted: only the last cleanup times, via `DefaultTableRuntime`. Everything else is rebuilt by
  `initHandler` on start (each leadership change starts a new `startOptimizingService`).
- The two constructors: `(Action, TableService, poolSize)` and `(TableService, poolSize)`, which
  uses `IcebergActions.SYSTEM`.

## Contracts

- Pool size and interval come from each subclass's config keys; see [`./inline`](inline/README.md).
- `loadTable(tableRuntime)` → `tableService.loadTable(identifier)` (a catalog call per run).

## Extending or changing it

Add a subclass in `./inline`, construct it in `InlineTableExecutors.setup` (with an enable flag in
`FusionManagementConf` if it is optional), expose a getter, and register it in
`FusionServiceContainer.startOptimizingService` with `addHandlerChain`. For a cleanup job, return a
`CleanupOperation` and override `shouldExecute` so a restart does not run it early. Catch
exceptions inside `execute`; `executeTask` re-arms in `finally`, but an exception skips
`persistUpdatingCleanupTime` and is otherwise swallowed by the executor.

Do not change the `finally` re-arm or the `scheduledTables` check casually: together they are the
only things that keep each table scheduled exactly once.

## Invariants and pitfalls

- If `enabled()` turns false, the table stops being scheduled. It comes back only through an event
  that calls `scheduleIfNecessary` (`handleTableAdded`, or a subclass's `handleConfigChanged` /
  `handleStatusChanged`). A subclass whose `enabled()` depends on changing state needs such a hook.
  `OptimizingCommitExecutor` uses `handleStatusChanged` for this.
- Fixed delay means drift. For `TableRuntimeRefreshExecutor`, whose cron check has a fixed 60 s
  window, this can skip a cron fire (see [`./inline`](inline/README.md)).
- With more tables than threads, runs queue up and every table's period grows.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/scheduler/inline/`:
`PeriodicTableSchedulerTestBase`, `TestPeriodicTableSchedulerCleanup`,
`TestConfigurableIntervalExecutors`, `ExecutorTestBase`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestPeriodicTableSchedulerCleanup -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`./inline`](inline/README.md): all subclasses.
- [`../process`](../process/README.md): `ActionCoordinatorScheduler` (inactive in Fusion).
- [`../optimizing`](../optimizing/README.md): what the cron tick and commit executor drive.
- `../table`: `RuntimeHandlerChain`, `DefaultTableService`, `DefaultTableRuntime`.
