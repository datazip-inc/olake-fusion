# `io.olake.fusion.server.manager`

Two things share this package: the server's plugin managers (metric reporters, event listeners)
and the optimizer containers, which start and stop optimizer processes when asked through the REST
API.

## Role in Fusion

Server side.

- **Plugin managers.** `FusionServiceContainer#startRestServices` creates the `EventsManager` and
  `MetricManager` singletons. `MetricManager#getGlobalRegistry` is the registry that table runtimes
  (`DefaultTableService`), optimizing queues (`OptimizingQueue`) and `AmsServiceMetrics` register
  metrics in; installed reporters (for example the Prometheus reporter in
  `fusion-metrics/fusion-metrics-prometheus`) read it.
- **Optimizer containers.** `FusionServiceContainer.ConfigurationHelper#initContainerConfig` turns
  the `containers:` list of `config.yaml` into `ContainerMetadata`, and `Containers` (in
  [`../resource/`](../resource/README.md)) instantiates each `container-impl` and calls `init`.
  `OptimizerGroupController#scaleOutOptimizer`, `OptimizerController#createOptimizer` and
  `OptimizerController#releaseOptimizer` call `requestResource` / `releaseResource` on them.

In Docker and Helm deployments the production optimizer (Spark, image `olakego/fusion-spark`) is
deployed and restarted by Kubernetes, and its group is external (see
[`../bootstrap/`](../bootstrap/README.md)), so no container here starts it. The containers matter
for local debugging (`LocalOptimizerContainer` behind the `local` group) and for telemetry: a
configured `SparkOptimizerContainer`'s `spark-conf.*` values seed
`FusionServiceContainer#getSparkConfig`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `PluginManager<T>` | Interface: `install`, `uninstall`, `get`, `installedPlugins`, `close`. | |
| `AbstractPluginManager<T>` | Loads `<conf dir>/plugins/<category>.yaml`, discovers plugins with `ServiceLoader` on the classpath and in `<plugin dir>/<category>/` (single jars or directories of jars), installs enabled ones (`open(properties)`), and visits them with `forEach` under the plugin's class loader. | `PluginConfiguration`, `ClassLoaderContext`, `Environments` |
| `PluginConfiguration` | One plugin entry: `name` (required), `enabled` (default `true`), `priority` (default `0`), `properties`. | `AbstractPluginManager` |
| `ClassLoaderContext` | `Closeable` that switches the thread context class loader to the plugin's loader and restores it. | `AbstractPluginManager#forEach` |
| `MetricManager` | Singleton manager for `MetricReporter` plugins (category `metric-reporters`) and owner of the global `MetricRegistry`. On `initialize` gives each reporter the registry and adds `MetricRegisterListener` reporters as listeners. | `MetricRegistry`, `AmsServiceMetrics` |
| `EventsManager` | Singleton manager for `EventListener` plugins (category `event-listeners`). `emit(event)` runs `handleEvent` on all listeners on one background thread. | `LoggingEventListener`, `RestCatalogService` |
| `AbstractOptimizerContainer` | Base `InternalResourceContainer`. `init` requires `ams-home` and `ams-optimizing-uri`. `requestResource` calls `doScaleOut` and merges the returned properties into the resource. Builds the optimizer CLI args (`-a`, `-p`, `-g`, `-hb`, `-eds -dsp`, `-msz`, `-ce`, `-cmts`, `-cmes`, `-ct`, `-id`) and `export` commands from `export.*` properties. | `Resource`, `OptimizerProperties` |
| `LocalOptimizerContainer` | Starts `<ams-home>/bin/optimizer.sh start <memory> <args>` through `/bin/sh -c`, with `OPTIMIZER_LOG_DIR_NAME=optimizer-<group>-<resourceId>`; memory is the group property `memory` (MB per thread) times the thread count. With the container property `use-server-classpath: "true"` it also exports `FUSION_OPTIMIZER_CLASSPATH` (the server's `java.class.path`), so the optimizer runs the server's classes instead of `lib/` jars. `releaseResource` runs `kill -9 <job-id>` (`taskkill` on Windows). | `ExecUtil` |
| `SparkOptimizerContainer` | Builds and runs `spark-submit` for `io.olake.fusion.optimizer.spark.SparkOptimizer`. Supports YARN (reads the application id from output) and `k8s://` masters (driver pod name `fusion-optimizer-<resourceId>`, labels). `releaseResource` runs `spark-submit --kill`. Nested `SparkConfKeys`, `SparkConf`, `SparkConf.Builder`. | Spark `SparkConf`, `ProcessBuilder` |
| `ExecUtil` | `exec(String[] command, List<String> output)`: runs a command, collects stdout/stderr on two daemon threads, logs stderr on non-zero exit, returns the exit code. | `LocalOptimizerContainer` |

## How it works

### Plugin loading (`AbstractPluginManager#initialize`)

1. `loadPluginConfigurations` reads `Environments.getConfigPath()/plugins/<category>.yaml` and the
   list under the key `<category>` (missing file -> no plugins). Entries are sorted by `priority`;
   duplicate names fail with `Duplicate plugin name found`.
2. `foundAvailablePlugins` runs `ServiceLoader.load(pluginType)` on the context class loader, then
   on a `URLClassLoader` per jar or per directory under `Environments.getPluginPath()/<category>`.
   Two different classes with the same `name()` throw `IllegalStateException`.
3. For each enabled entry, `install(name)` calls `plugin.open(properties)`. A configured name with
   no implementation throws `LoadingPluginException`; installing twice throws
   `AlreadyExistsException`.
4. `close()` uninstalls (calls `close()` on) every installed plugin.

### Starting an optimizer through a container

1. `OptimizerGroupController#scaleOutOptimizer` (`POST /api/ams/v1/optimize/optimizerGroups/{optimizerGroup}/optimizers`, body `{"parallelism": n}`)
   builds a `Resource` from the group's container and properties, gets the container with
   `Containers.get`, calls `requestResource`, then `OptimizerManager#createResource` (row in
   `resource`).
2. `AbstractOptimizerContainer#requestResource` -> `doScaleOut(resource)`:
   - `LocalOptimizerContainer`: `ExecUtil.exec` of the shell command; returns no properties.
     The `job-id` (`Resource.PROPERTY_JOB_ID`) that `releaseResource` needs comes from the
     optimizer itself: `StandaloneOptimizer` registers its process id under that key, and
     `OptimizerController#releaseOptimizer` copies the registered optimizer's properties into the
     resource before calling `releaseResource`.
   - `SparkOptimizerContainer`: runs the `spark-submit` command from
     `buildOptimizerStartupArgsString`; returns `kubernetes-submission-id`
     (`<namespace>:fusion-optimizer-<resourceId>`) for `k8s://` masters, or `yarn-application-id`
     parsed from the output within 10 minutes.
3. The started optimizer authenticates to `DefaultOptimizingService` with the `-a` URI, group `-g`
   and resource id `-id`.

### Spark options

`SparkConf.Builder#build` takes every `spark-conf.<key>` from the container properties, then from
the group properties (group wins), strips the prefix, and emits `--conf key=value`.
`buildOptimizerStartupArgsString` also defaults `spark.dynamicAllocation.enabled=true` and
`spark.dynamicAllocation.maxExecutors=<thread count>`. `init` defaults `master` to `yarn` and
`deploy-mode` to `client`, defaults `job-uri` to `<ams-home>/plugin/optimizer/spark/optimizer-job.jar`,
and requires `spark.kubernetes.container.image` for `k8s://` masters. `--proxy-user` is
`export.HADOOP_USER_NAME` or `hadoop`.

## State, threads and concurrency

- `MetricManager.INSTANCE`, `EventsManager.INSTANCE`: double-checked-locking singletons, reset by
  `dispose()`.
- `AbstractPluginManager`: `installedPlugins` and `foundedPlugins` are `ConcurrentHashMap`s;
  `pluginConfigs` is a `LinkedHashMap` written only during `initialize`.
- `EventsManager` thread `PluginVisitor-event-listeners-0` (daemon, `ThreadPoolExecutor` with core
  0, max 1, unbounded `LinkedBlockingQueue`): events are delivered in order, one at a time.
- `ExecUtil` creates two daemon reader threads per command. `ExecUtil.exec` blocks the caller (the
  HTTP request thread) until the command exits.
- Containers hold no state between calls apart from their parsed properties. Started optimizers
  are tracked in the `resource` table, not here.

## Contracts

- Plugin config files: `<conf dir>/plugins/metric-reporters.yaml` (key `metric-reporters`) and
  `<conf dir>/plugins/event-listeners.yaml` (key `event-listeners`). Jars in
  `<plugin dir>/metric-reporters/` and `<plugin dir>/event-listeners/`.
- `META-INF/services/io.olake.fusion.resource.ResourceContainer` lists the two containers
  (`SparkOptimizerContainer`, `LocalOptimizerContainer`).
- `config.yaml` `containers:` entries (`name`, `container-impl`, `properties`). Container property
  keys:
  - common: `ams-home`, `ams-optimizing-uri` (both injected by the server), `export.<ENV>`.
  - local: group property `memory`; env `OPTIMIZER_JAVA_OPTS` is passed through.
  - spark: `spark-home` (required), `master`, `deploy-mode`, `job-uri`, `spark-conf.<spark key>`.
- Resource properties written back: `job-id` (local), `kubernetes-submission-id` /
  `yarn-application-id` (spark).
- Kubernetes labels on Spark driver pods: `optimizer-group`, `optimizer-implementation`
  (`spark-native-kubernetes`), `optimizer-id`.

## Extending or changing it

- New metric reporter or event listener: implement the plugin interface from `fusion-core`, add it
  to `META-INF/services`, and list it in the matching plugins yaml. Do not subclass the managers.
- New plugin category: subclass `AbstractPluginManager<YourPlugin>` with a category string, as
  `MetricManager` does. The generic type argument must be set on the direct superclass, because
  the constructor reads it by reflection.
- New container: extend `AbstractOptimizerContainer`, implement `doScaleOut` and
  `releaseResource`, register in `META-INF/services/io.olake.fusion.resource.ResourceContainer`,
  and reference it by class name in `containers:`. Copy `LocalOptimizerContainer`.
- The CLI flags built in `AbstractOptimizerContainer#buildOptimizerStartupArgsString` must match
  the optimizer's argument parser in `fusion-optimizer-common`; change both together.

## Invariants and pitfalls

- `SparkOptimizerContainer.SparkConfKeys.KUBERNETES_EXECUTOR_LABEL_PREFIX` is
  `"spark.kubernetes.driver.label."`, the same as the driver prefix, so the executor labels in
  `addKubernetesProperties` are written as driver labels and executor pods get none.
- `SparkOptimizerContainer#init` checks `deployMode.equals(DeployMode.CLUSTER.name())`, comparing
  the enum with a `String`; it is always false, so the "`job-uri` required in cluster mode" check
  never runs.
- `SparkOptimizerContainer#doScaleOut` and `#releaseResource` build `"<exports> && <command>"`. With
  no `export.*` properties the command starts with `" && "`, which `/bin/sh` rejects.
  `LocalOptimizerContainer` handles the empty case.
- `buildReleaseYarnCommand` / `buildReleaseKubernetesCommand` use `{}` placeholders in Guava
  `Preconditions` messages, which only format `%s`.
- Plugin managers read `plugins/<category>.yaml`, not `config.yaml`. An `event-listeners:` block in
  `config.yaml` (as in `local-test/config.yaml`) has no effect.
- `releaseResource` for local optimizers is `kill -9`; the optimizer gets no chance to finish its
  task, which is then retried by the server after the heartbeat timeout.
- Plugin exceptions inside `forEach` are logged and swallowed, so a broken reporter fails silently.

## Tests

In `fusion-ams/src/test/java/io/olake/fusion/server/manager/`: `TestAbstractPluginManager`
(discovery, install, priority, duplicates; plugins registered in
`src/test/resources/META-INF/services/...TestAbstractPluginManager$TestPlugin`),
`TestSparkOptimizerContainer`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestSparkOptimizerContainer -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../resource/`](../resource/README.md): `Containers`, `ContainerMetadata`, `OptimizerManager`.
- [`../events/`](../events/README.md): `LoggingEventListener`.
- [`..`](../README.md): `FusionServiceContainer` (container config, Spark telemetry config),
  `AmsServiceMetrics`.
- [`../dashboard/`](../dashboard/README.md): `OptimizerGroupController`, `OptimizerController`.
