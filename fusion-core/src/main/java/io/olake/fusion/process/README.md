# `io.olake.fusion.process`

Generic process and task model: process status enums, persisted process state, the
`StagedTaskDescriptor` that carries a task's input/output, and `SimpleFuture` used to signal task and
process completion.

## Role in Fusion

Server side. Used by:

- the optimizing path: `RewriteStageTask` extends `StagedTaskDescriptor`; `TaskRuntime`
  (fusion-ams) wraps it with a `SimpleFuture`; `ProcessStatus` is the status of an optimizing
  process (Run in OLake-UI) stored in `table_process`;
- the generic process service in `fusion-ams/.../server/process/` (`ProcessService`,
  `DefaultTableProcessStore`, `TableProcessExecutor`), which uses `TableProcess`,
  `TableProcessStore`, `ProcessEvent` and `ProcessFactory`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `ProcessStatus` | `UNKNOWN, PLANNING, PENDING, SUBMITTED, RUNNING, CANCELING, SUCCESS, CANCELED, CLOSED, KILLED, FAILED, SKIPPED`. `SKIPPED` is Fusion's: cron fired but nothing to do. `toStage()` uses the ordinal as weight. | `OptimizingQueue`, `DefaultTableRuntime.recordSkippedOptimization` |
| `ProcessTaskStatus` | `PLANNED, SCHEDULED, ACKED, FAILED, SUCCESS, CANCELED`. | Task views |
| `ProcessStage` | Display stage (`desc`, `weight`); `ARBITRARY` for single-stage processes. | Dashboard |
| `ProcessEvent` | Events with priority: `SUBMIT_REQUESTED`, `COMPLETE_SUCCESS`, `COMPLETE_FAILED`, `RETRY_REQUESTED` (10), `CANCEL_REQUESTED`, `KILL_REQUESTED` (100). | `TableProcessStore.tryTransitState`, `ProcessService` |
| `FusionProcess` | Process contract: submit and complete futures, `store()`, defaults for parameters, summary, status, id, action. | `TableProcess` |
| `TableProcess` | Abstract process bound to a `TableRuntime` and a `TableProcessStore`; `getSubmitFuture()` completes on submit **or** complete. | `ProcessService` |
| `TableProcessStore` | Persisted process metadata and atomic updates (`begin()...commit()`), `tryTransitState(...)`. | `DefaultTableProcessStore` |
| `TableProcessState` / `ProcessState` | In-memory state with `@StateField`s (id, status, retry number, times, failed reason); status setters stamp `endTime`. | |
| `ProcessFactory` | `create(tableRuntime, action)` and `recover(tableRuntime, state)`. | |
| `StagedTaskDescriptor<I, O, S>` | Holds `tableId`, `input`, `volatile output`, `volatile summary`, `properties`; `setOutputBytes` deserializes and recalculates the summary; `extractProtocolTask` serializes the input into a Thrift `OptimizingTask`. | `RewriteStageTask`, `TaskRuntime` |
| `SimpleFuture` | Wrapper on `CompletableFuture` with ordered callbacks, `complete()`, `join()`, `reset()`, `and/or/allOf/anyOf`. | `TaskRuntime`, `TableProcess` |

## How it works

- **Task wire format.** `StagedTaskDescriptor.extractProtocolTask(taskId)` builds an `OptimizingTask`
  with `SerializationUtil.simpleSerialize(input)` and the task properties. The optimizer's result
  bytes come back through `setOutputBytes`, which calls the subclass's `deserializeOutput` and
  `calculateSummary()`. `reset()` clears the output for a retry.
- **Status timestamps.** `TableProcessState.setStatus` sets `endTime` for `SUCCESS`, `FAILED`,
  `KILLED`; moving into `SUBMITTED` from another status clears `endTime`, `failedReason` and `summary`.
  `addRetryNumber()` increments the retry count and resets status to `PENDING`.
- **`SimpleFuture`.** `whenCompleted(runnable)` chains callbacks; the internal completed future is
  completed only after the **last registered** callback has run without exception. `complete()`
  triggers the chain and waits for it in the caller thread; exceptions from callbacks are rethrown
  as `FusionRuntimeException` and leave the future not done. `reset()` rebuilds the chain with the
  same callbacks.

## State, threads and concurrency

- `StagedTaskDescriptor.output` / `summary` and several `TableProcessState` fields are `volatile`
  because they are written by Thrift handler threads and read by scheduler and dashboard threads.
- `SimpleFuture` callbacks run in the thread that calls `complete()`; a callback may call `reset()`,
  which `complete()` tolerates.

## Contracts

- `ProcessStatus` names are persisted (for example `PENDING`, `SKIPPED` in `table_process.status`) and
  returned by the optimizing-process APIs that OLake-UI shows as run history. Do not rename or
  reorder values: `toStage()` uses the ordinal.
- The `OptimizingTask` input is Java serialization of the task input; see
  [`optimizing`](../optimizing/README.md#contracts) for compatibility rules.
- `ProcessEvent` priorities decide which event wins in `tryTransitState`.

## Extending or changing it

New process kinds implement `TableProcess` with a `ProcessFactory`; new task kinds extend
`StagedTaskDescriptor`. Adding a `ProcessStatus` value: append it at the end, and check OLake-UI's
handling of unknown statuses.

## Invariants and pitfalls

- `calculateSummary()` is called from the `StagedTaskDescriptor` constructor, before subclass fields
  are initialized; subclasses must not rely on their own fields there (`RewriteStageTask` only uses
  `input` and `output`).
- `TableProcessState.getQuotaValue()` always returns `1`; `ProcessState.getQuotaValue()` default
  divides by elapsed time.

## Tests

`fusion-core/src/test/java/io/olake/fusion/process/TestSimpleFuture.java`

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-core -am -Dtest=TestSimpleFuture -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../optimizing`](../optimizing/README.md): `RewriteStageTask`.
