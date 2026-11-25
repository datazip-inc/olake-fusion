# `io.olake.fusion.server.optimizing.sorter`

Comparators that decide which `PENDING` table `SchedulingPolicy` plans first when several tables
are due at the same time.

## Role in Fusion

When an optimizer thread polls and no task is ready, `OptimizingQueue.scheduleTableIfNecessary`
calls `SchedulingPolicy.scheduleTable(skipSet)`. That method filters the group's tables down to the
eligible ones (status `PENDING`, not planning, not blocked) and returns the **minimum** under the
comparator from the configured `SorterFactory`. With `optimizer.max-planning-parallelism = 1`
(the default) this decides the order in which due tables are planned, one at a time.

The sorter only orders tables. It does not decide whether a table is due; the cron tick
(`TableRuntimeRefreshExecutor`) does that by setting `PENDING`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `SorterFactory` | Plugin interface: `getIdentifier()` and `createComparator()`. | `SchedulingPolicy` |
| `QuotaOccupySorter` | Identifier `quota` (the default). Lowest `DefaultTableRuntime.calculateQuotaOccupy()` first. | `DefaultTableRuntime` |
| `BalancedSorter` | Identifier `balanced`. Oldest last run first: compares `max(lastFullOptimizingTime, lastMinorOptimizingTime, lastMajorOptimizingTime)`. | `DefaultTableRuntime` |

## How it works

1. `SchedulingPolicy`'s static block loads all `SorterFactory` implementations with
   `ServiceLoader` into `sorterFactoryCache`, keyed by `getIdentifier()`. A later one with the same
   identifier replaces an earlier one. Registration file:
   `fusion-ams/src/main/resources/META-INF/services/io.olake.fusion.server.optimizing.sorter.SorterFactory`
   (lists `QuotaOccupySorter` and `BalancedSorter`).
2. `SchedulingPolicy.setTableSorterIfNeeded(group)` reads the optimizer group property
   `scheduling-policy`, default `QuotaOccupySorter.IDENTIFIER` (`quota`). It runs in the
   constructor and on `OptimizingQueue.updateOptimizerGroup` (group sync every
   `self-optimizing.refresh-group-interval`).
3. Each `scheduleTable` call runs `createSorterByPolicy()`, which builds a **new** comparator. An
   unknown name throws `IllegalArgumentException("Unsupported scheduling policy: ...")`.

`QuotaOccupySorter`'s comparator stores each table's weight in a `HashMap` on first use, so every
table's `calculateQuotaOccupy()` is computed once per scheduling pass. That value is the table's
task run time in the last hour (`QUOTA_LOOK_BACK_TIME`) divided by one hour and by its quota limit
(`self-optimizing.quota`: taken as a thread count when > 1, else `ceil(quota * threads)`). Tables
that used less of their share go first.

## State, threads and concurrency

No state of its own. Comparators run inside `SchedulingPolicy.scheduleTable`, under
`SchedulingPolicy.tableLock`, on the Thrift thread that is polling, while it holds
`OptimizingQueue.scheduleLock`. A slow comparator therefore delays every optimizer poll.

## Contracts

- Optimizer group property `scheduling-policy`: `quota` (default) or `balanced`. Identifiers are
  case-sensitive.
- `SorterFactory.createComparator()` returns a raw `Comparator`; it is used as
  `Comparator<DefaultTableRuntime>`.

## Extending or changing it

Add a class implementing `SorterFactory` with a unique identifier, list it in the services file
above, and set `scheduling-policy` on the optimizer group. Keep the comparator cheap: no catalog or
Postgres calls per comparison.

## Invariants and pitfalls

- The sorter cannot make a table eligible. Changing it never makes a table run more often; it only
  changes who goes first when several are `PENDING`.
- Planning time affects run type: the planner checks the crons again with a 60 s window (see
  [`../README.md`](../README.md)). A table that waits behind others for more than a minute can be
  planned as a different type, or as an empty run.

## Tests

No dedicated tests in this package. Ordering is exercised through `TestOptimizingQueue`
(`fusion-ams/src/test/java/io/olake/fusion/server/optimizing/`):

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test \
  -pl fusion-ams -am -Dtest=TestOptimizingQueue -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md): `SchedulingPolicy`, `OptimizingQueue`.
- `../../table`: `DefaultTableRuntime.calculateQuotaOccupy`.
