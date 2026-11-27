# `io.olake.fusion.server.table.cleanup`

Types that record when each table-maintenance job last ran for a table, so periodic schedulers can wait their interval across restarts.

## Role in Fusion

- `scheduler/PeriodicTableScheduler` asks each executor for its `CleanupOperation` and reads/writes the last run time through `table.DefaultTableRuntime.getLastCleanTime(operation)` and `updateLastCleanTime(operation, time)`.
- The executors in `scheduler/inline` return their operation: `SnapshotsExpiringExecutor` → `SNAPSHOTS_EXPIRING`, `DataExpiringExecutor` → `DATA_EXPIRING`, `OrphanFilesCleaningExecutor` → `ORPHAN_FILES_CLEANING`, `DanglingDeleteFilesCleaningExecutor` → `DANGLING_DELETE_FILES_CLEANING`. Other executors use the default `NONE`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `CleanupOperation` | Enum: `DANGLING_DELETE_FILES_CLEANING`, `ORPHAN_FILES_CLEANING`, `DATA_EXPIRING`, `SNAPSHOTS_EXPIRING`, `NONE`. `NONE` means no timestamp is stored. | `PeriodicTableScheduler`, inline executors |
| `TableRuntimeCleanupState` | Plain bean with `lastOrphanFilesCleanTime`, `lastDanglingDeleteFilesCleanTime`, `lastDataExpiringTime`, `lastSnapshotsExpiringTime` (epoch ms, default 0). | `DefaultTableRuntime.CLEANUP_STATE_KEY` |

## How it works

`DefaultTableRuntime` declares `StateKey.stateKey("cleanup_state").jsonType(TableRuntimeCleanupState.class)` and includes it in `REQUIRED_STATES`. The bean is stored as JSON in `table_runtime_state.state_value` for key `cleanup_state`. `getLastCleanTime` switches on the operation (returning `0` for `NONE`); `updateLastCleanTime` updates the matching field inside one `store().begin().updateState(...).commit()`.

## State, threads and concurrency

No state of its own. Writes are serialized by the runtime store's `tableLock`.

## Contracts

- Row `table_runtime_state` with `state_key = 'cleanup_state'`, JSON field names as in `TableRuntimeCleanupState` getters (Jackson).

## Extending or changing it

To add a cleanup job: add an enum value, a field with getter/setter here, and a `case` in both `DefaultTableRuntime.getLastCleanTime` and `updateLastCleanTime`; return the value from the executor. Old JSON without the field deserializes with the default 0.

## Invariants and pitfalls

- Renaming a field changes the JSON key; existing rows then read as 0 and the job runs immediately once.
- Adding an enum value without the `switch` cases silently stores nothing.

## Tests

`fusion-ams/src/test/java/io/olake/fusion/server/scheduler/inline/TestPeriodicTableSchedulerCleanup.java`:

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestPeriodicTableSchedulerCleanup -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`table`](../README.md): `DefaultTableRuntime` state keys.
- `../../scheduler`: `PeriodicTableScheduler` and the inline executors.
