# `io.olake.fusion.optimizer.standalone`

The standalone optimizer: one plain JVM that runs the common optimizer loop with no Spark. It is
for local debugging only; production compaction runs on the Spark optimizer.

## Role in Fusion

- `StandaloneOptimizer.main` is the main class named in `dist/src/main/fusion-bin/bin/optimizer.sh`
  (`RUN_SERVER="io.olake.fusion.optimizer.standalone.StandaloneOptimizer"`).
- The server starts it. `io.olake.fusion.server.manager.LocalOptimizerContainer.doScaleOut` runs
  `/bin/sh -c "<exports> && export OPTIMIZER_LOG_DIR_NAME=optimizer-<group>-<resourceId> && <amsHome>/bin/optimizer.sh start <memory> -a ... -p ... -g ... [-id ...]"`.
  This happens when an optimizer is created in a group whose container is `localContainer`
  (`io.olake.fusion.server.manager.LocalOptimizerContainer` in `local-test/config.yaml`), for
  example with `POST /api/ams/v1/optimize/optimizerGroups/local/optimizers` (see `CLAUDE.md`, section 5).
- `<memory>` is the group property `memory` (MB per thread) × thread count. `optimizer.sh` turns it
  into `-Xmx<memory>m`, adds `-XX:+ExitOnOutOfMemoryError`, GC logging and `--add-opens` flags, and
  starts the JVM with `nohup` in the background.
- It talks to the server over Thrift exactly like the Spark optimizer (`OptimizingService` at the `-a`
  URL); see the common package.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `StandaloneOptimizer` | `main`: parses `OptimizerConfig`, builds an `Optimizer`, sets `memorySize` from `Runtime.getRuntime().maxMemory()` (MB), registers `job-id` = this JVM's process id (from `RuntimeMXBean.getName()`), then calls `startOptimizing()`. | `Optimizer`, `OptimizerConfig`, `Resource.PROPERTY_JOB_ID` |

## How it works

1. `new OptimizerConfig(args)`; a bad argument throws `CmdLineException` and the JVM exits.
2. `new Optimizer(config)` creates one `OptimizerToucher` and `executionParallel` plain
   `OptimizerExecutor`s.
3. `memorySize` and `job-id` are set before start, so they go out with `authenticate`.
4. `startOptimizing()` starts the executor threads; the toucher runs on the main thread. Each task
   runs in its executor thread, in this JVM: `OptimizerExecutor.executeTask` →
   `IcebergRewriteExecutorFactory` → `IcebergRewriteExecutor.execute()` (`fusion-core`).

## State, threads and concurrency

One JVM, one toucher (main thread), `-p` executor threads (`Optimizer-executor-<i>`), each running
one task at a time. All tasks share the heap set by `-Xmx`, and the `DeleteCache` singleton when
`-ce` is set.

## Contracts

- Arguments: `OptimizerConfig` options, built by `AbstractOptimizerContainer.buildOptimizerStartupArgsString`.
- `job-id` is the OS process id. `LocalOptimizerContainer.releaseResource` reads it and runs
  `kill -9 <pid>` (or `taskkill` on Windows) to release the optimizer.
- Classpath: `optimizer.sh` puts `$FUSION_CONF_DIR/optimize` and every jar under `$FUSION_HOME/lib`
  on `CLASSPATH`, and exits if there are none.
- Logging: `OPTIMIZER_LOG_CONF_FILE`, default `${FUSION_CONF_DIR}/optimize/log4j2.xml`, which writes
  `optimizer-debug.log`, `optimizer-info.log` and `optimizer-error.log` under `-Dlog.home`
  (`$FUSION_LOG_DIR/<OPTIMIZER_LOG_DIR_NAME>`). Stderr goes to `optimizer.log.err` in the same
  directory. `src/main/resources/log4j2.xml` (console only) is for running from an IDE; the
  `maven-jar-plugin` leaves it out of the jar.
- Extra JVM options: `OPTIMIZER_JAVA_OPTS` in the server's environment is exported to the script;
  so are container properties prefixed `export.`.

## Extending or changing it

Keep this entry point thin. Loop behaviour belongs in `fusion-optimizer-common` and rewrite
behaviour in `fusion-core`; both then apply to the Spark optimizer as well. Only add code here for
something that is specific to a local JVM, as with `memorySize` and `job-id`.

## Invariants and pitfalls

- **It runs jars, not compiled classes.** `optimizer.sh` loads `dist/src/main/fusion-bin/lib/*.jar`
  when the server runs from source. After changing `fusion-core`, `fusion-optimizer-common` or this
  module, rebuild the jar with `package` and copy it into that directory, or the optimizer keeps
  running the old code. `make sync-libs` fills or restores the directory.
- It is a separate process. Stopping the server does not stop it; kill it by PID
  (`ps -eo pid,args | grep "[S]tandaloneOptimizer "`) before starting a new one with new jars.
- Its log directory is `dist/src/main/fusion-bin/logs/optimizer-<group>-<resourceId>/`. The server log
  line `Starting local optimizer using command` gives the id.
- Do not use it to judge production behaviour such as memory, parallelism or retries. The Spark
  optimizer runs tasks on separate executors, and Spark can retry a task when an executor is lost.

## Tests

The module has no tests of its own (its pom declares `fusion-core`'s test jar, but there is no
`src/test`). The loop it runs is tested in `fusion-optimizer-common`. To try it, run the server from
source and start a local optimizer as described in `CLAUDE.md`, section 5.

## Related packages

- [`io.olake.fusion.optimizer.common`](../../../../../../../../../fusion-optimizer-common/src/main/java/io/olake/fusion/optimizer/common/): the loop.
- [`io.olake.fusion.optimizer.spark`](../../../../../../../../../fusion-optimizer-spark/src/main/java/io/olake/fusion/optimizer/spark/): the production optimizer.
- [`io.olake.fusion.server.manager`](../../../../../../../../../../fusion-ams/src/main/java/io/olake/fusion/server/manager/) in `fusion-ams`: `LocalOptimizerContainer`, `AbstractOptimizerContainer`.
