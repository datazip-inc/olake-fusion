# `io.olake.fusion.server.process`

A generic framework, inherited from Amoro, for running table "actions" as tracked processes on
pluggable engines. In Fusion it is wired in but installs no plugins, so it does nothing at runtime.
Compaction does not use it. `TableProcessMeta`, the bean behind the `table_process` row, is the
part of this package that compaction does use.

## Role in Fusion

- **Compaction (live path).** A Run (an optimizing process) is a row in `table_process`. It is
  created by `DefaultTableRuntime.markAsPending` on a cron tick, then updated by `OptimizingQueue`
  and `DefaultTableRuntime` through `TableProcessMapper`. `TableProcessMapper.getProcessMeta`
  returns a `TableProcessMeta`. `OptimizingQueue` uses it to recover a run on startup, and
  `DefaultTableRuntime.triggeredTypeName` uses it to read the run's type. OLake-UI's run history
  (`.../optimizing-processes`) is read from these rows.
- **Action framework (wired, inactive).** `FusionServiceContainer.startOptimizingService` builds
  `new ProcessService(serviceConfig, tableService)` and adds `processService.getTableHandlerChain()`
  to the table handler chain, after the optimizing service's handler. `ProcessService.initialize`
  loads plugins through `ActionCoordinatorManager` (category `action-coordinators`) and
  `ExecuteEngineManager` (category `execute-engines`).
  `AbstractPluginManager.loadPluginConfigurations` returns an empty list when
  `<conf>/plugins/<category>.yaml` does not exist. Neither `dist/src/main/fusion-bin/conf/plugins/`
  nor `local-test/plugins/` has those files, and no production class implements `ActionCoordinator`
  or `ExecuteEngine` (only test mocks do). So no coordinator or engine is installed, and
  `recoverProcesses` matches nothing.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `TableProcessMeta` | Bean of one `table_process` row: `processId`, `tableId`, `externalProcessIdentifier`, `status` (`ProcessStatus`), `processType`, `processStage`, `executionEngine`, `retryNumber`, `createTime`, `finishTime`, `failMessage`, `processParameters`, `summary`. Also `copy()`, `fromTableProcessStore`, `fromTableProcessState` (deprecated), `of(...)`. | `TableProcessMapper`, `OptimizingQueue`, `DefaultTableRuntime` |
| `ProcessService` | Registers, recovers, retries and cancels `TableProcess`es; keeps installed coordinators/engines and `activeTableProcess`; its `ProcessRuntimeHandler` (a `RuntimeHandlerChain`) forwards table events to the coordinators. | `ActionCoordinatorScheduler`, `TableProcessExecutor`, `TableProcessMapper` |
| `ActionCoordinator` | Plugin interface (`ActivePlugin`) for one `Action`: `formatSupported`, `parallelism`, `executionEngine`, `getNextExecutingTime`, `enabled`, `getExecutorDelay`, `createTableProcess`, `recoverTableProcess`, `cancelTableProcess`, `retryTableProcess`. Constants `PROPERTY_PARALLELISM = "parallelism"`, `SNOWFLAKE_ID_GENERATOR`. | `TableProcess` (`fusion-core`) |
| `ActionCoordinatorScheduler` | `PeriodicTableScheduler` that forwards every decision to its coordinator; `execute` → `createTableProcess` + `ProcessService.register`. `PROCESS_MAX_RETRY_NUMBER = 3`. | `ProcessService`, `../scheduler` |
| `DefaultTableProcessStore` | `TableProcessStore` over a `TableProcessMeta`: the process status machine (`tryTransitState`) and staged, transactional updates (`begin()` → `TableProcessOperation` → `commit()`). | `TableProcessMapper` |

## How it works

These flows only run if a coordinator plugin is installed.

### Register and execute

1. `ActionCoordinatorScheduler.execute(tableRuntime)` → `coordinator.createTableProcess` →
   `ProcessService.register`.
2. `register`: if `hasAliveTableProcess` (same action, status `RUNNING`, `SUBMITTED` or `PENDING`),
   log and skip. Otherwise `persistTableProcess` (`TableProcessMapper.insertProcess`),
   `trackTableProcess`, then `executeOrTraceProcess`.
3. `executeOrTraceProcess`: skip if the status is `CANCELING`/`CANCELED`. Otherwise build a
   `TableProcessExecutor` for the engine named by `store().getExecutionEngine()` and submit it to
   `processExecutionPool`. The finish callback: if the status is `FAILED`,
   `retryNumber < PROCESS_MAX_RETRY_NUMBER` and the table runtime is set →
   `tryTransitState(PENDING, RETRY_REQUESTED, ...)` and `scheduler.retry(process)`. Otherwise
   `untrackTableProcessInstance`.

### Recover, cancel, table removal

- `recoverProcesses(tableRuntimes)`: `TableProcessMapper.selectAllActiveProcesses` (status
  `SUBMITTED` or `RUNNING`). For each row whose `processType` matches an installed coordinator's
  action name: `scheduler.recover(runtime, new DefaultTableProcessStore(..., PROCESS_MAX_RETRY_NUMBER))`
  → `ProcessService.recover` → track and execute. Optimizing runs (`processType` `MINOR`/`MAJOR`/
  `FULL`, status `RUNNING`) are returned by this query but match no coordinator.
- `cancel(process)` → `cancelProcess`: `tryTransitState(CANCELED, CANCEL_REQUESTED, ...)`,
  untrack, then `executeEngine.tryCancelTableProcess`.
- `ProcessRuntimeHandler.handleTableRemoved`: forward to the coordinators, then `cancel` every
  tracked process of the table.

### Process status machine (`DefaultTableProcessStore.tryTransitState`)

Under `transitionLock`:

1. If the event's priority is ≥ `KILL_REQUESTED` (100; `CANCEL_REQUESTED` is also 100), set
   `terminalRequested` and raise `highestPriority`.
2. Refuse if the current status `isTerminal`: `SUCCESS`, `KILLED`, `CLOSED`, `CANCELED`, or `FAILED`
   with `retryNumber >= maxRetryTime`.
3. Once a priority-100 event has been seen, refuse every event except `CANCEL_REQUESTED` and
   `KILL_REQUESTED`.
4. `validTransition`:

| Event | From | To |
| --- | --- | --- |
| `SUBMIT_REQUESTED` | `UNKNOWN`, `PENDING`, `SUBMITTED`, `RUNNING` | any (engine status), and stores the external id |
| `COMPLETE_SUCCESS` | `SUBMITTED`, `RUNNING`, `PENDING` | `SUCCESS` |
| `COMPLETE_FAILED` | `SUBMITTED`, `RUNNING`, `PENDING` | `FAILED` (+ fail message) |
| `CANCEL_REQUESTED` | `UNKNOWN`, `SUBMITTED`, `RUNNING`, `PENDING` | `CANCELED` |
| `KILL_REQUESTED` | `UNKNOWN`, `SUBMITTED`, `RUNNING`, `PENDING` | `KILLED` |
| `RETRY_REQUESTED` | `FAILED` | `PENDING`, `retryNumber + 1`, external id cleared |

5. Persist through `begin()...commit()`. `TableProcessOperationImpl` applies the staged changes to a
   `copy()` of the meta, writes `TableProcessMapper.updateProcess` in a `NestedSqlSession` under
   `tableProcessLock`, commits (rolls back and rethrows as `PersistenceException` on error), and
   only then copies the fields back to the live meta (`visitable`).

`TableProcessExecutor.run` sends `KILL_REQUESTED` for a `CLOSED` engine status, but
`validTransition` only lets `KILL_REQUESTED` go to `KILLED`, so that transition is refused and
logged.

## State, threads and concurrency

- `processExecutionPool`: `ThreadPoolExecutor(10, 100, 60 s, LinkedBlockingQueue)`. The queue is
  unbounded, so the pool never grows past 10 threads. Default thread names.
- `activeTableProcess`: `ConcurrentHashMap<ServerTableIdentifier, Map<Long, TableProcess>>`, in
  memory only; rebuilt from `table_process` by `recoverProcesses`.
- `DefaultTableProcessStore`: `transitionLock` (serialises transitions), `tableProcessLock`
  (around the persist), volatile `terminalRequested`, `AtomicInteger highestPriority`.
- `dispose()` closes both plugin managers, shuts the pool down and clears the map.
  `ProcessRuntimeHandler.doDispose` is a TODO.

## Contracts

- Postgres: `table_process` through `TableProcessMapper` (`insertProcess`, `updateProcess`,
  `getProcessMeta`, `selectAllActiveProcesses`, `deleteBefore`).
- Plugin config files: `<conf>/plugins/action-coordinators.yaml`, `<conf>/plugins/execute-engines.yaml`
  (absent in Fusion).
- `fusion-core` types: `io.olake.fusion.process.TableProcess`, `TableProcessStore`,
  `ProcessStatus` (`UNKNOWN, PLANNING, PENDING, SUBMITTED, RUNNING, CANCELING, SUCCESS, CANCELED,
  CLOSED, KILLED, FAILED, SKIPPED`), `ProcessEvent`, `io.olake.fusion.Action`.

## Extending or changing it

The framework is the extension point (`ActionCoordinator` + `ExecuteEngine` plugins), but Fusion's
compaction does not go through it. Do not move compaction into it without a design decision. If you
change `TableProcessMeta` or the `table_process` columns, check `TableProcessMapper`, the dashboard
process endpoints, and OLake-UI's table list (`server/internal/services/optimization/table.go`),
which reads the latest Minor/Major/Full process.

## Invariants and pitfalls

- `table_process` is shared with compaction. `selectAllActiveProcesses` returns `RUNNING`
  optimizing runs, so a coordinator whose action name equals `MINOR`, `MAJOR` or `FULL` would take
  them over at startup.
- `DefaultTableProcessStore.dispose()` runs `TableProcessMapper.deleteBefore(tableId, processId)`,
  which deletes **every** row of the table with `process_id <= processId`, the run history
  included.
- `ProcessRuntimeHandler.formatSupported` accepts every format.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/TestDefaultProcessService.java`, using
`process/MockActionCoordinator`, `MockExecuteEngine`, `MockTableProcess`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestDefaultProcessService -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`./executor`](executor/README.md): `TableProcessExecutor`, `ExecuteEngine`.
- [`../scheduler`](../scheduler/README.md): `PeriodicTableScheduler`.
- [`../optimizing`](../optimizing/README.md): the compaction runs that share `table_process`.
- `../table`: `RuntimeHandlerChain`, `DefaultTableRuntime`.
