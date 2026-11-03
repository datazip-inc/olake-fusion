# `io.olake.fusion.optimizer.spark`

The production optimizer. It is a Spark application whose driver runs the common optimizer loop
(register, heartbeat, poll, ack, complete) and sends each compaction task to a Spark executor as a
one-partition Spark job.

## Role in Fusion

- Module `fusion-optimizer/fusion-optimizer-spark`, artifact
  `fusion-optimizer-spark-${spark.major.version}_${scala.binary.version}` (default Spark 3.5.8,
  Scala 2.13). Java only; the module has no Scala sources.
- The shade plugin builds
  `target/fusion-optimizer-spark-3.5_2.13-0.9-SNAPSHOT-jar-with-dependencies.jar`. Spark is
  `provided`; `slf4j-api`, `org.apache.hadoop:*` and `org.apache.hive:*` are left out, and
  `org.apache.parquet` / `org.apache.orc` are relocated to `org.apache.amoro.shade.org.apache.*` so
  they do not clash with Spark's own copies.
- `docker/optimizer-spark/Dockerfile` copies that jar to `/opt/spark/usrlib/optimizer-job.jar` in an
  `apache/spark:3.5.8-java17` image, adds the AWS SDK v2 `bundle` and `url-connection-client` jars to
  `$SPARK_HOME/jars`, and installs `docker/optimizer-spark/log4j2.xml`. The image is
  `olakego/fusion-spark` (built by the `docker-optimizer-spark` job in
  `.github/workflows/docker-images.yml`). The dist tarball also carries the jar as
  `plugin/optimizer/spark/optimizer-job.jar` (`dist/src/main/assemblies/bin.xml`).

### How it is started

**Kubernetes-managed (current).** A Kubernetes Deployment runs `spark-submit` in **client** mode,
so the pod itself is the Spark driver; Kubernetes restarts it when it dies. The server neither
starts nor stops it. `io.olake.fusion.server.bootstrap.OptimizerBootstrap` makes sure the group
named by `ams.optimizer.bootstrap.group-name` (`spark-container`) exists as an external group, and
the pod registers into it on its own. Reference: `local-test/spark-optimizer.yaml` (applied by the
`kind-load-image` service in `local-test/docker-compose.yml`). The main points of that manifest:

- `--master k8s://https://kubernetes.default.svc --deploy-mode client --class io.olake.fusion.optimizer.spark.SparkOptimizer local:///opt/spark/usrlib/optimizer-job.jar`
- optimizer args `-a thrift://10.210.30.10:1261 -p 1 -g spark-container -id fusion-optimizer`
- a headless Service `fusion-optimizer` (ports 7078 driver RPC, 7079 block manager) so executors can
  reach the driver (`spark.driver.host=fusion-optimizer.spark.svc.cluster.local`);
- `spark.kubernetes.driver.pod.name=$(POD_NAME)`, so executor pods are owned by the driver pod and
  deleted with it;
- dynamic allocation with `spark.dynamicAllocation.maxExecutors=1`, `spark.executor.cores=1`,
  `spark.executor.memory=1g`, `spark.driver.memory=1g`;
- `spark.executor.maxNumFailures=2147483647`, `spark.executor.failuresValidityInterval=10ms`,
  `spark.task.maxFailures=4`, so lost executors never end the Spark context;
- `-XX:+ExitOnOutOfMemoryError` on the driver, so a driver OOM restarts the pod.

OLake-Helm starts the same image with `--class org.apache.amoro.optimizer.spark.SparkOptimizer`
(according to the shim's Javadoc); see
[`org.apache.amoro.optimizer.spark`](../../../../../org/apache/amoro/optimizer/spark/).

**Server-submitted (still in the code).** `io.olake.fusion.server.manager.SparkOptimizerContainer`
can run `<spark-home>/bin/spark-submit ... --class io.olake.fusion.optimizer.spark.SparkOptimizer <job-uri> <args>`
for a group whose container is a Spark container (`sparkContainer` in `local-test/config.yaml`,
`deploy-mode: cluster`). With the bootstrap group set to external, Fusion's deployments do not use
this path.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `SparkOptimizer` | `main`: parses `OptimizerConfig`, creates the `SparkSession` (app name `amoro-spark-optimizer-<resourceId>`), computes `memorySize`, adds Spark register properties, then calls `startOptimizing()`. Extends `Optimizer` and supplies `SparkOptimizerExecutor`s. | `Optimizer`, `OptimizerToucher`, `JavaSparkContext` |
| `SparkOptimizerExecutor` | Runs on the driver. Extends `OptimizerExecutor` and overrides `executeTask(OptimizingTask)` to run the task as a Spark job. | `JavaSparkContext`, `SparkOptimizingTaskFunction` |
| `SparkOptimizingTaskFunction` | Runs on a Spark executor. A Spark `Function<OptimizingTask, OptimizingTaskResult>` that sets the logging context and calls the static `OptimizerExecutor.executeTask`. | `OptimizerExecutor`, `OptimizingTaskLogContext`, SLF4J `MDC` |

## How it works

### Driver start-up (`SparkOptimizer.main`)

1. `new OptimizerConfig(args)`; `SparkSession.builder().appName(...).getOrCreate()`; wraps it in a
   `JavaSparkContext`.
2. Logs a warning if `spark.dynamicAllocation.enabled` is false.
3. Sets `memorySize` (MB) = `spark.driver.memory` + `ceil(executionParallel / spark.executor.cores)` ×
   `spark.executor.memory` (defaults `1g`, `1g`, `1`), using `org.apache.spark.util.Utils.memoryStringToMb`.
   This is what the server shows as the optimizer's memory; it is not a limit.
4. Builds the optimizer (`executionParallel` `SparkOptimizerExecutor`s) and adds register properties:
   `job-id` = the Spark application id, and `spark.driver.memory`, `spark.executor.memory`,
   `spark.executor.cores` as set in the Spark conf, or empty when not set. The server copies these into
   its telemetry (`FusionServiceContainer.updateSparkConfig`).
5. `optimizer.startOptimizing()`: executor threads start, and the toucher loop runs on the main
   thread.

### Per task

Steps 1, 2 and 4 are the common loop (`OptimizerExecutor.start`): `pollTask`, `ackTask`,
`completeTask`, all on the driver. Step 3 is overridden:

1. `SparkOptimizerExecutor.executeTask` puts `processId` and `logFilePath=<processId>/driver` in the
   MDC, so driver logs for this task go to `<LOG_DIR>/<processId>/driver.log`.
2. It sets the job description to `Amoro rewrite files task, table name:<table>, task id:<id>`. To
   get the table name it deserializes the task input on the driver; any input that is not a
   `RewriteFilesInput` fails the task with `IllegalArgumentException`.
3. It runs `jsc.parallelize(ImmutableList.of(task), 1).map(new SparkOptimizingTaskFunction(config, threadId)).collect()`
   and takes the single result. The task and `OptimizerConfig` are Java-serialized to the executor;
   the `OptimizingTaskResult`, which carries the serialized `RewriteFilesOutput`, comes back through
   `collect()`.
4. On the executor, `SparkOptimizingTaskFunction.call` calls
   `OptimizingTaskLogContext.setContext(processId, taskId)`, puts `processId`, `taskId` and
   `logFilePath=<processId>/<taskId>` in the MDC, and calls
   `OptimizerExecutor.executeTask(config, threadId, task, LOG)`. That runs the factory named in the
   task, `io.olake.fusion.optimizing.IcebergRewriteExecutorFactory` →
   `IcebergRewriteExecutor.execute()` from `fusion-core`, which reads the input files and writes the
   new data and delete files to the table's storage. The context is cleared in `finally`.
5. Any `Throwable` in steps 1 to 3 on the driver (a failed job, a lost driver connection) becomes an
   `OptimizingTaskResult` with `errorMessage` set.

### Logging

`docker/optimizer-spark/log4j2.xml` routes events from `io.olake.fusion*` loggers by the
`logFilePath` context key to `${LOG_DIR}/<logFilePath>.log` (JSON lines; `LOG_DIR` defaults to
`/mnt/amoro-logs/compaction`), and to the console otherwise. The Deployment mounts `/mnt/amoro-logs`
from the host on the driver and the executors; the comments in `local-test/config.yaml` state that
the server's `LogController` reads from that path.

## State, threads and concurrency

- **Driver:** one toucher (main thread) and `executionParallel` threads named
  `Optimizer-executor-<i>`. Each thread runs at most one task, so at most `executionParallel` Spark
  jobs run at a time. Each job has one partition, so one Spark task, which uses one executor core.
- **Executors:** run `SparkOptimizingTaskFunction` only. They never talk to the Fusion server; all
  Thrift calls are made on the driver. They need table storage access (the Deployment passes
  `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` from the `fusion-s3-credentials` secret).
- Spark's executor task threads are reused, which is why `SparkOptimizingTaskFunction` clears the
  MDC and `OptimizingTaskLogContext` in `finally`.
- For full use of executors, the warning in `main` recommends `spark.dynamicAllocation.maxExecutors`
  = `executionParallel` (with one core per executor).

## Contracts

- Main class `io.olake.fusion.optimizer.spark.SparkOptimizer`, plus the legacy name
  `org.apache.amoro.optimizer.spark.SparkOptimizer`. Both are referenced from outside the repo
  (OLake-Helm, `local-test/spark-optimizer.yaml`, `SparkOptimizerContainer.SPARK_JOB_MAIN_CLASS`).
- Application arguments: the `OptimizerConfig` options (see the common package). The deployment
  sets `-a`, `-p`, `-g` and `-id`; `-g` must match the bootstrap group (`spark-container`), and a
  stable `-id` lets the server retire the old registration when the pod restarts.
- Spark conf keys read: `spark.dynamicAllocation.enabled`, `spark.driver.memory`,
  `spark.executor.memory`, `spark.executor.cores` (`OptimizerProperties.SPARK_*` for the last three).
- Jar path in the image: `/opt/spark/usrlib/optimizer-job.jar` (`local:///opt/spark/usrlib/optimizer-job.jar`).
  The Dockerfile `ARG OPTIMIZER_JOB` names the jar to copy.
- Image tag: the compose stacks and `local-test/spark-optimizer.yaml` use
  `olakego/fusion-spark:latest` with `IfNotPresent`, for both the driver and
  `spark.kubernetes.container.image` (executors).
- Driver and executors run the same image, so they always run the same code. Server and optimizer
  can differ during a rollout; see "Rollouts" in the common package for the tag check and for the
  serialized task input and output.

## Extending or changing it

- Change how a task is dispatched in `SparkOptimizerExecutor.executeTask`. Keep the result contract:
  always return an `OptimizingTaskResult` with the same task id and thread id; never throw.
- Change what runs on the executor in `SparkOptimizingTaskFunction`. Everything it holds must be
  `Serializable`.
- A new register property for telemetry: add the key to `OptimizerProperties`, add it in
  `SparkOptimizer.main`, and read it in `FusionServiceContainer`.
- A new Spark setting in the deployment: change `local-test/spark-optimizer.yaml` and OLake-Helm's
  `templates/fusion/`, not this code.

## Invariants and pitfalls

- **A rewrite can run more than once.** `OptimizerExecutor.executeTask` turns task errors into
  results, so Spark does not retry them. But if an executor is lost mid-task (pod OOM-killed or
  evicted), Spark re-runs the task on another executor, up to `spark.task.maxFailures` times. The
  first attempt's files are then never committed. The server may also retry a task whose result it
  never received (see the common package).
- A driver restart loses every running task. The new driver registers with a new token, and the
  server reschedules the old tasks after its timeouts.
- The task result travels to the driver through `collect()`. Very large outputs count against
  `spark.driver.maxResultSize` and driver memory.
- The comment in `SparkOptimizingTaskFunction` says that `AbstractRewriteFilesExecutor.execute()`
  checks `OptimizingTaskLogContext.isContextSet()`. Nothing in the current tree calls
  `isContextSet()`. The MDC values set here are what the log routing uses.
- `docker/optimizer-spark/log4j2.xml` must stay in the image. The Dockerfile deletes Spark's default
  `log4j2.properties` so that this file is used.
- The job description and app name still say "Amoro". Nothing parses them.
- Optimizer-side changes reach Kubernetes only through a new `olakego/fusion-spark` image. See the
  end-to-end section of `CLAUDE.md` for the local build, which must be tagged
  `olakego/fusion-spark:latest`.

## Tests

`fusion-optimizer/fusion-optimizer-spark/src/test/java/io/olake/fusion/optimizer/spark/TestSparkOptimizerShim.java`
(JUnit 5) checks that the legacy class `org.apache.amoro.optimizer.spark.SparkOptimizer` exists,
is a separate class, and has a public static `main`. There are no tests that run Spark. The shared
loop is tested in `fusion-optimizer-common`.

```shell
./mvnw -o test -pl fusion-optimizer/fusion-optimizer-spark -am -Drat.skip=true \
  -Dtest=TestSparkOptimizerShim -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`io.olake.fusion.optimizer.common`](../../../../../../../../../fusion-optimizer-common/src/main/java/io/olake/fusion/optimizer/common/): the loop this package builds on.
- [`org.apache.amoro.optimizer.spark`](../../../../../org/apache/amoro/optimizer/spark/): legacy main-class shim.
- [`io.olake.fusion.optimizing`](../../../../../../../../../../fusion-core/src/main/java/io/olake/fusion/optimizing/) in `fusion-core`: `IcebergRewriteExecutor`, the code that runs on the executors.
- [`io.olake.fusion.server.manager`](../../../../../../../../../../fusion-ams/src/main/java/io/olake/fusion/server/manager/) in `fusion-ams`: `SparkOptimizerContainer`.
- [`io.olake.fusion.server.bootstrap`](../../../../../../../../../../fusion-ams/src/main/java/io/olake/fusion/server/bootstrap/) in `fusion-ams`: `OptimizerBootstrap`.
