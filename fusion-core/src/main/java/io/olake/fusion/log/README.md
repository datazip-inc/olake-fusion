# `io.olake.fusion.log`

`OptimizingTaskLogContext`: puts the optimizing process id and task id into the Log4j2
`ThreadContext` of the thread that runs a task, so that task logs can be tagged and routed per task.

## Role in Fusion

**Optimizer.** `fusion-optimizer-spark`'s `SparkOptimizingTaskFunction#call` (runs on Spark
executors) calls `setContext(processId, taskId)` before running the task and `clearContext()` in
`finally`. The server does not use it.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizingTaskLogContext` | Static helpers: `setContext(long processId, int taskId)`, `clearContext()`, `isContextSet()`. Keys `PROCESS_ID_KEY = "processId"`, `TASK_ID_KEY = "taskId"`, `LOG_FILE_PATH_KEY = "logFilePath"`. | Log4j2 `ThreadContext`, `SparkOptimizingTaskFunction`, `conf/optimize/log4j2-routing.xml` |

## How it works

1. `setContext` sets a thread-local flag and puts `processId` and `taskId` into `ThreadContext`.
2. `SparkOptimizingTaskFunction` then also puts `processId`, `taskId` and
   `logFilePath = "<processId>/<taskId>"` into SLF4J `MDC` (the same Log4j2 context map).
3. `dist/src/main/fusion-bin/conf/optimize/log4j2-routing.xml` routes on `${ctx:logFilePath}`: when
   it is set, logs go to `${LOG_DIR}/<processId>/<taskId>.log`; the patterns print
   `%X{processId}` and `%X{taskId}`. The Spark driver sets `logFilePath` itself in
   `SparkOptimizerExecutor`.
4. `clearContext` removes the flag and the three keys.

## State, threads and concurrency

All state is per thread (`ThreadLocal` flag and Log4j2's thread context). Spark reuses executor
threads, so `clearContext` must run in `finally`, as it does today.

## Contracts

The key names `processId`, `taskId` and `logFilePath` are used by the optimizer's Log4j2 routing
configuration. They are bundled in the optimizer image, so code and config change together.

## Extending or changing it

Add a key constant here, put it in `setContext`, remove it in `clearContext`, and reference it in
`log4j2-routing.xml`.

## Invariants and pitfalls

- `isContextSet()` has no caller in the repo. The comment in `SparkOptimizingTaskFunction` says
  `AbstractRewriteFilesExecutor.execute()` checks it, but no such check exists in the current tree.
- `setContext` does not set `logFilePath`; `clearContext` removes it anyway.

## Tests

No unit tests. Check per-task log files with a local optimizer run (see CLAUDE.md, "Code that runs in
the optimizer").

## Related packages

- [`io.olake.fusion`](../README.md), `fusion-optimizer/fusion-optimizer-spark`
