# `io.olake.fusion.log`

`OptimizingTaskLogContext`: puts the optimizing process id, and on a Spark executor the task id,
into the Log4j2 `ThreadContext` of the thread that runs a task, so that the Spark optimizer can ship
that thread's log lines to AMS.

## Role in Fusion

**Optimizer.** In `fusion-optimizer-spark`, `SparkOptimizerExecutor#executeTask` (driver) calls
`setDriverContext(processId)` and `SparkOptimizingTaskFunction#call` (executors) calls
`setExecutorContext(processId, taskId)`; both call `clearContext()` in `finally`.
`SparkOptimizingLogSupport`'s appender ships only lines whose context has a `processId`.

**Server.** `LogWriter` (`fusion-ams`, `server/optimizing`) uses `LOST_LINES_TASK_ID` to recognise
a report of lines the optimizer dropped.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizingTaskLogContext` | Static helpers: `setDriverContext(long processId)`, `setExecutorContext(long processId, int taskId)`, `clearContext()`. Keys `PROCESS_ID_KEY = "processId"`, `TASK_ID_KEY = "taskId"`; `LOST_LINES_TASK_ID = -1`. | Log4j2 `ThreadContext`, `SparkOptimizingLogSupport`, `LogWriter` |

## How it works

1. The driver thread of a task gets only `processId`; an executor thread gets `processId` and
   `taskId`.
2. `SparkOptimizingLogSupport.LogAppender` formats each line of such a thread as one JSON object
   and buffers it. A line without `taskId` is sent with task id 0, which AMS writes to
   `<LOG_DIR>/<processId>/driver.log`; an executor line goes to `<LOG_DIR>/<processId>/<taskId>.log`.
3. Executors send their lines to the driver over Spark RPC; the driver sends all lines to AMS with
   `OptimizingService.appendLogs`. AMS (`LogWriter`) is the only writer of the files.
4. `clearContext` removes both keys.

## State, threads and concurrency

All state is Log4j2's per-thread context. Spark reuses executor threads, so `clearContext` must run
in `finally`, as it does today.

## Contracts

- The key names `processId` and `taskId` are read by `SparkOptimizingLogSupport`'s appender and
  printed in the shipped JSON lines that `LogController` parses.
- `LOST_LINES_TASK_ID` is part of the `appendLogs` wire contract between the optimizer and AMS.

## Extending or changing it

Add a key constant here, set it in the `set*Context` methods, remove it in `clearContext`, and add
it to `SparkOptimizingLogSupport.LogAppender`'s JSON pattern if AMS should store it.

## Tests

No unit tests. Check per-task log files with a Spark optimizer run.

## Related packages

- [`io.olake.fusion`](../README.md), `fusion-optimizer/fusion-optimizer-spark`,
  `fusion-ams` `server/optimizing` (`LogWriter`)
