# `io.olake.fusion.optimizer.common`

The optimizer runtime that every Fusion optimizer shares. It registers with the Fusion server over
Thrift, sends heartbeats, and runs a set of worker threads. Each thread polls a compaction task,
runs it with the rewrite executor named in the task, and reports the result.

## Role in Fusion

This code runs in the optimizer process, never in the server. Two entry points build on it:

- **Spark optimizer (production).** `io.olake.fusion.optimizer.spark.SparkOptimizer` in
  `fusion-optimizer-spark` (image `olakego/fusion-spark`) runs as a Spark driver on Kubernetes. It
  subclasses `Optimizer` and swaps in `SparkOptimizerExecutor`, so each task runs as a Spark job.
  See [the Spark package](../../../../../../../../../fusion-optimizer-spark/src/main/java/io/olake/fusion/optimizer/spark/).
- **Standalone optimizer (local debugging only).** `io.olake.fusion.optimizer.standalone.StandaloneOptimizer`
  uses `Optimizer` as it is. The server starts it through `bin/optimizer.sh` from
  `LocalOptimizerContainer` (`localContainer` in `local-test/config.yaml`). See
  [the standalone package](../../../../../../../../../fusion-optimizer-standalone/src/main/java/io/olake/fusion/optimizer/standalone/).

The optimizer is always the Thrift client. It talks to the server's `OptimizingService`
(`fusion-core/src/main/thrift/amoro_optimizing_service.thrift`, served by
`io.olake.fusion.server.DefaultOptimizingService`) at the URL given by `-a`, for example
`thrift://<host>:1261` (`ams.thrift-server.optimizing-service.bind-port`). Clients come from
`io.olake.fusion.client.OptimizingClientPools.getClient(url)`, a per-JVM pool cache keyed by URL.
The server never calls the optimizer.

Fusion has one optimizer group and one optimizer. In helm and Docker deployments the group is
`spark-container`, created on server start by `io.olake.fusion.server.bootstrap.OptimizerBootstrap`
as an external group (`ams.optimizer.bootstrap.group-name`).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizerConfig` | Command-line options, parsed with args4j (`new OptimizerConfig(String[])`). `Serializable`, so the Spark optimizer can ship it to executors. | `io.olake.fusion.OptimizerProperties` (option long names and defaults) |
| `AbstractOptimizerOperator` | Base of the toucher and executors. Holds the config, the auth token (`AtomicReference<String>`) and the `stopped` flag. `callAms` and `callAuthenticatedAms` wrap every Thrift call with the retry rules below. | `OptimizingClientPools`, `io.olake.fusion.ErrorCodes`, `AmoroException` |
| `OptimizerToucher` | Registers the optimizer (`authenticate`), then sends `touch` every `heartBeat` ms. Re-registers when the server forgets the token. Pushes each new token to a `TokenChangeListener`. | `OptimizerRegisterInfo`, `Optimizer.SetTokenToExecutors` |
| `OptimizerExecutor` | One worker thread: `pollTask`, `ackTask`, `executeTask`, `completeTask`, in a loop. The static `executeTask(OptimizerConfig, int, OptimizingTask, Logger)` does the actual rewrite and is reused on Spark executors. | `OptimizingExecutorFactory`, `TaskProperties`, `SerializationUtil`, `DeleteCache` |
| `Optimizer` | Builds one `OptimizerToucher` and `executionParallel` `OptimizerExecutor`s, starts them, and wires token changes from the toucher to all executors. The protected constructor takes factories so subclasses can supply their own executors. | `OptimizerToucher`, `OptimizerExecutor` |

## How it works

### Start-up (`Optimizer`)

1. The constructor creates the toucher and `config.getExecutionParallel()` executors with thread ids
   `0..p-1`. It adds register properties: `resource-id` (`OptimizerProperties.RESOURCE_ID`) when
   `-id` is set, and `optimizer-tag` = `OptimizerProperties.OPTIMIZER_TAG_VALUE` (currently
   `"v0.1.5"`).
2. The entry point (`SparkOptimizer.main` or `StandaloneOptimizer.main`) sets `memorySize` and adds
   `job-id` (`Resource.PROPERTY_JOB_ID`) before calling `startOptimizing()`.
3. `startOptimizing()` starts one thread per executor, named `Optimizer-executor-<threadId>`, then
   attaches `SetTokenToExecutors` to the toucher and runs `toucher.start()` **on the calling
   thread**. That call blocks until the toucher is stopped, which keeps `main` alive.

### Registration and heartbeat (`OptimizerToucher.start`)

Loop while not stopped:

1. `checkToken()`: when there is no token, it adds `heart-beat-interval` to the register properties
   and calls `authenticate(OptimizerRegisterInfo)` with `threadCount = executionParallel`,
   `memoryMb = memorySize`, `groupName`, `resourceId`, `startTime` (when the toucher was built) and
   the register properties. The returned token is stored and passed to
   `TokenChangeListener.tokenChange`, which sets it on every executor.
   - If the server answers `FORBIDDEN_ERROR_CODE` (1004), the process calls `System.exit(1)`. The
     server sends this when `heart-beat-interval` is not lower than its `optimizer.heart-beat-timeout`,
     or when an optimizer without `optimizer-tag` registers while a tagged one is registered in the
     group (`DefaultOptimizingService.retireUntaggedRegistrations`).
   - Any other `TException` is logged; the toucher tries again after `heartBeat` ms.
2. `touch()`: calls `touch(token)`. On `PLUGIN_RETRY_AUTH_ERROR_CODE` (2006, the server does not
   know the token, for example after a server restart or after it expired the optimizer) it clears
   the token, so the next loop registers again and every executor gets the new token.
3. Sleeps `heartBeat` ms (`-hb`, default 10000).

### Task loop (`OptimizerExecutor.start`)

Loop while not stopped:

1. `pollTask()`: calls `pollTask(token, threadId)` until it returns a task, sleeping
   `callAmsInterval` (5 s) between empty polls. The server itself waits up to
   `optimizer.polling-timeout` (default 3 s) inside the call.
2. `ackTask(task)`: calls `ackTask(token, threadId, taskId)`. If it throws, the task is dropped
   locally; the server resets it after `optimizer.task-ack-timeout`.
3. `executeTask(task)`: the instance method calls the static `executeTask`, which
   - copies the delete-cache options to JVM system properties (`fillTaskProperties`);
   - builds task properties from `task.getProperties()` plus `process-id`,
     `extend-disk-storage` (when `-eds`), `memory-storage-size` (bytes, `-msz` × 1024 × 1024) and
     `disk-storage-path`;
   - deserializes `task.getTaskInput()` with `SerializationUtil.simpleDeserialize` into a
     `TableOptimizing.OptimizingInput`;
   - creates the factory named by `task-executor-factory-impl`
     (`TaskProperties.TASK_EXECUTOR_FACTORY_IMPL`) through Iceberg's `DynConstructors`. For Iceberg
     tables the server planner (`io.olake.fusion.optimizing.plan.IcebergPartitionPlan`) sets it to
     `io.olake.fusion.optimizing.IcebergRewriteExecutorFactory`, whose `createExecutor` returns an
     `IcebergRewriteExecutor` (subclass of `AbstractRewriteFilesExecutor`, in `fusion-core`);
   - calls `factory.initialize(properties)`, `createExecutor(input)` and `execute()`, then
     serializes the `OptimizingOutput` into `OptimizingTaskResult.taskOutput` and copies
     `output.summary()`.
   Any `Throwable` becomes an `OptimizingTaskResult` with `errorMessage` set (trimmed to
   `ERROR_MESSAGE_MAX_LENGTH`, 4000 chars). Execution errors are therefore reported, never thrown.
4. `completeTask(result)`: calls `completeTask(token, result)` in the `finally` block, for success
   and failure. If it fails for good, the result is only logged.

### Retry rules (`AbstractOptimizerOperator`)

| Error | `callAms` (toucher) | `callAuthenticatedAms` (executors) |
| --- | --- | --- |
| No token yet | not applicable | wait `callAmsInterval`, check again |
| `TApplicationException.MISSING_RESULT` (Thrift `null` return, for example an empty `pollTask`) | return `null` | return `null` |
| `AmoroException` `PLUGIN_RETRY_AUTH_ERROR_CODE` | rethrow (the toucher handles it) | clear this executor's token (`resetToken`, compare-and-set), wait for a new one, retry |
| `AmoroException` `PERSISTENCE_ERROR_CODE` or `UNDEFINED_ERROR_CODE` | wait, retry | wait, retry |
| Any other `AmoroException` (for example task not found, illegal task state) | rethrow | rethrow |
| Any non-`AmoroException` `Throwable` (connection refused, transport errors) | wait, retry | wait, retry |

Retries never give up while the operator is started; after `stop()` the call throws
`IllegalStateException("Operator is stopped")`. `callAmsInterval` is static, 5 s, and can be changed
with `AbstractOptimizerOperator.setCallAmsInterval` (tests use 500 ms).

## State, threads and concurrency

- One toucher, run on the thread that calls `startOptimizing()`.
- `executionParallel` executor threads. The server tracks each as an `OptimizerThread` (token +
  thread id) and binds a task to it on poll; `ack` and `complete` must come from the same token and
  thread id (`TaskRuntime.validThread` on the server).
- Each executor keeps its own copy of the token. The toucher pushes new tokens to all of them; an
  executor that hits an auth error clears only its own copy and waits.
- Nothing is shared between executors besides the config, the client pool, and JVM system
  properties set by `fillTaskProperties`.
- `stop()` only sets a volatile flag. It does not interrupt a running rewrite or a blocked Thrift
  call. Neither entry point calls `stopOptimizing()` or installs a shutdown hook; the process is
  stopped by being killed.

## Contracts

### Thrift calls (`OptimizingService`)

`authenticate(OptimizerRegisterInfo) -> token`, `touch(token)`, `pollTask(token, threadId)`,
`ackTask(token, threadId, OptimizingTaskId)`, `completeTask(token, OptimizingTaskResult)`. The
client pool also calls `ping()` to validate connections. `cancelProcess` is not used by the
optimizer.

### Command-line options (`OptimizerConfig`)

| Short | Long (`OptimizerProperties`) | Field | Default |
| --- | --- | --- | --- |
| `-a` | `--ams-optimizing-uri` | `amsUrl` | required |
| `-p` | `--execution-parallel` | `executionParallel` | required |
| `-g` | `--group-name` | `groupName` | required |
| `-hb` | `--heart-beat-interval` | `heartBeat` (ms) | 10000 |
| `-eds` | `--extend-disk-storage` | `extendDiskStorage` | false |
| `-dsp` | `--disk-storage-path` | `diskStoragePath` | none |
| `-msz` | `--memory-storage-size` | `memoryStorageSize` (MB) | 512 |
| `-id` | `--resource-id` | `resourceId` | none |
| `-ce` | `--cache-enabled` | `cacheEnabled` | false |
| `-cmts` | `--cache-max-total-size` | `cacheMaxTotalSize` | `128mb` |
| `-cmes` | `--cache-max-entry-size` | `cacheMaxEntrySize` | `64mb` |
| `-ct` | `--cache-timeout` | `cacheTimeout` | `10min` |

`memorySize` has no option; the entry point computes it. The server builds these arguments in
`AbstractOptimizerContainer.buildOptimizerStartupArgsString` from optimizer group properties of the
same names. For the Kubernetes-managed optimizer they are written directly in the Deployment
(`local-test/spark-optimizer.yaml`: `-a thrift://10.210.30.10:1261 -p 1 -g spark-container -id fusion-optimizer`).

### Register properties sent with `authenticate`

`resource-id`, `optimizer-tag`, `heart-beat-interval` (from this package), plus `job-id` and, for
Spark, `spark.driver.memory`, `spark.executor.memory`, `spark.executor.cores` (from the entry
points). The server uses `optimizer-tag` to tell current optimizers from older ones and
`resource-id` to retire the registration of a restarted optimizer
(`DefaultOptimizingService.retirePreviousRegistrations`).

### Server timeouts that matter here (`ams.optimizer.*` in `config.yaml`)

`heart-beat-timeout` (must be greater than `-hb`), `task-ack-timeout`, `task-execute-timeout`,
`polling-timeout`.

### Rollouts

- A new optimizer always sends `optimizer-tag`. When it registers, the server retires untagged
  registrations in the group.
- An old, untagged optimizer is refused with `FORBIDDEN` while a tagged one is registered, and exits.
- Changing `OPTIMIZER_TAG_VALUE` alone changes nothing on the server; it only checks that the tag is
  not blank.
- Task input and output cross the wire as Java-serialized objects (`SerializationUtil`). Server and
  optimizer must have compatible versions of `RewriteFilesInput`, `RewriteFilesOutput` and the Iceberg
  classes inside them; deploy both from the same build.

## Extending or changing it

- New executor behaviour: subclass `OptimizerExecutor` and override the protected
  `executeTask(OptimizingTask)` (as `SparkOptimizerExecutor` does), then pass a factory to the
  protected `Optimizer` constructor.
- New option: add an `@Option` field with getter and setter to `OptimizerConfig` and add it to
  `toString()`. If the server must pass it, add the key to `OptimizerProperties` and to
  `AbstractOptimizerContainer.buildOptimizerStartupArgsString`, and add it to the Deployment args in
  OLake-Helm and `local-test/spark-optimizer.yaml`.
- New task property for executors: add it in `fillTaskProperties` and read it in `fusion-core`
  (`TaskProperties`).
- New rewrite behaviour belongs in `fusion-core` (`io.olake.fusion.optimizing`), not here.

## Invariants and pitfalls

- **A task can run more than once, and a result can arrive late.** If `ackTask` or `completeTask`
  is lost, or the optimizer misses heartbeats, the server retries the task
  (`DefaultOptimizingService.OptimizerKeeper`). A late `completeTask` from the old token or thread
  is rejected by `TaskRuntime.validThread`; the executor logs it and moves on. Files written by a
  discarded attempt are not committed.
- After an auth error, `callAuthenticatedAms` retries the same call with the new token. For
  `completeTask` the server rejects the result in `TaskRuntime.validThread` when the task is still
  bound to the old token.
- `fillTaskProperties` sets JVM-wide system properties, and `DeleteCache.getInstance()` reads them
  only once, when the cache is first created. Changing cache options between tasks has no effect.
- Known bugs in `fillTaskProperties`: the max-total-size branch uses
  `DeleteCache.DELETE_CACHE_MAX_TOTAL_SIZE_DEFAULT` (`"128mb"`) as the property **key**, so a
  non-default `-cmts` never reaches `DeleteCache`. The timeout branch compares the value with the key
  `DeleteCache.DELETE_CACHE_TIMEOUT` instead of the default, so it always sets
  `delete-cache-timeout` (harmless, it sets the configured value).
- `pollTask`, `ackTask` and `completeTask` loop until success or stop when the server is
  unreachable. A long outage blocks every thread, so no new tasks start.
- `FORBIDDEN` on `authenticate` kills the JVM with `System.exit(1)`. On Kubernetes the pod is then
  restarted and registers again.
- The local optimizer runs the compiled classes when started from source (`make debug-optimizer`,
  or `localContainer` with `use-server-classpath`). After editing this module, recompile and
  restart the optimizer (see `CLAUDE.md`, "Code that runs in the optimizer").

## Tests

Tests live in `fusion-optimizer/fusion-optimizer-common/src/test/java/io/olake/fusion/optimizer/common/`
and use JUnit 4 with an in-process mock server, `io.olake.fusion.TestAms` (from `fusion-core`'s test
jar, `fusion-core/src/test/java/io/olake/fusion/TestAms.java`):

- `TestOptimizerConfig`: argument parsing and required options.
- `TestOptimizerToucher`: registration, register properties, and re-registration after the server
  forgets the token.
- `TestOptimizerExecutor`: waiting for a token, success and failure results, using a test
  `OptimizingExecutorFactory`.
- `TestOptimizer`: end to end with two threads and two tasks.
- `TestOptimizerIcebergOnlyClasspath`: mixed-hive classes must not be on the optimizer classpath.

Run one test:

```shell
./mvnw -o test -pl fusion-optimizer/fusion-optimizer-common -am -Drat.skip=true \
  -Dtest=TestOptimizerExecutor -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`io.olake.fusion.optimizer.spark`](../../../../../../../../../fusion-optimizer-spark/src/main/java/io/olake/fusion/optimizer/spark/): production entry point and Spark executor.
- [`io.olake.fusion.optimizer.standalone`](../../../../../../../../../fusion-optimizer-standalone/src/main/java/io/olake/fusion/optimizer/standalone/): local debugging entry point.
- [`io.olake.fusion.optimizing`](../../../../../../../../../../fusion-core/src/main/java/io/olake/fusion/optimizing/) in `fusion-core`: `OptimizingExecutorFactory`, `IcebergRewriteExecutor`, `TaskProperties`.
- [`io.olake.fusion.client`](../../../../../../../../../../fusion-core/src/main/java/io/olake/fusion/client/) in `fusion-core`: `OptimizingClientPools`.
- [`io.olake.fusion.server`](../../../../../../../../../../fusion-ams/src/main/java/io/olake/fusion/server/) in `fusion-ams`: `DefaultOptimizingService`, the other end of every call.
