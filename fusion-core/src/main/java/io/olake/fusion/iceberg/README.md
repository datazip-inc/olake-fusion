# `io.olake.fusion.iceberg`

Holds one constant class, `io.olake.fusion.iceberg.Constants`, with `INVALID_SNAPSHOT_ID = -1L`:
the value Fusion uses for "no snapshot".

## Role in Fusion

The constant is shared by the **server** and the planning and maintenance code in `fusion-core`
that the server runs:

- `fusion-ams`: `server.utils.IcebergTableUtil`, `server.table.DefaultTableRuntime`,
  `server.optimizing.UnKeyedTableCommit`, `server.scheduler.inline.TableHealthScoreExecutor`.
- `fusion-core`: `optimizing.TableRuntimeOptimizingState` (initial `currentSnapshotId` and
  `currentChangeSnapshotId`), `optimizing.plan.AbstractOptimizingPlanner`,
  `formats.iceberg.utils.IcebergTableUtil`, `formats.iceberg.maintainer.IcebergTableMaintainer`.

The optimizer does not reference it directly.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `Constants` | `INVALID_SNAPSHOT_ID = -1L`. | the classes listed above |

## How it works

Code that reads a table's current snapshot returns `INVALID_SNAPSHOT_ID` when the table has none
(for example `IcebergTableUtil` when `currentSnapshot()` is null). Callers compare against it before
planning or committing; for example `UnKeyedTableCommit` only validates against a target snapshot
when it is not `INVALID_SNAPSHOT_ID`.

## State, threads and concurrency

None.

## Contracts

`-1` is also a persisted value: `TableRuntimeOptimizingState` is stored as JSON in PostgreSQL's
`table_runtime_state` under the state key `optimizing_state` (`DefaultTableRuntime`).

## Extending or changing it

Do not change the value: persisted state and every comparison depend on it.

## Invariants and pitfalls

- Iceberg snapshot ids are positive, so `-1` cannot collide with a real id.
- Not to be confused with `io.olake.fusion.Constants` (thrift service names) in the root package.

## Tests

Covered indirectly, for example by `fusion-core`
`optimizing/evaluation/TestMetadataBasedEvaluationEvent`.

## Related packages

- [`io.olake.fusion`](../README.md), `optimizing`, `formats/iceberg`
