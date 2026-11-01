# `io.olake.fusion.server`

The server's root package: the process entry point (`FusionServiceContainer`), the server
configuration schema (`FusionManagementConf`), the Thrift service that optimizers talk to
(`DefaultOptimizingService`), and the Iceberg REST catalog endpoints (`RestCatalogService`).

## Role in Fusion

Everything here runs in the Fusion server (the AMS process, image `olakego/fusion`). It wires the
rest of `fusion-ams` together:

- `FusionServiceContainer#main` loads `config.yaml`, opens the PostgreSQL data source, starts the
  HTTP server (REST API used by OLake-UI, Amoro web UI, Iceberg REST catalog), and, once this node
  is leader, starts the optimizing side: table service, inline table executors, optimizer
  bootstrap and the two Thrift servers.
- `DefaultOptimizingService` is the server half of the compaction lifecycle that the optimizer sees:
  the Spark optimizer authenticates, heartbeats (`touch`), polls tasks, acks them and reports
  results through it. It holds one `OptimizingQueue` per optimizer group and hands task requests to
  that queue. Planning, scheduling and commit live in `optimizing/` and `scheduler/`.
- `TableManagementService` is the legacy `AmoroTableMetastore` Thrift service (port 1260) over the
  catalog and table managers.

Fusion runs one optimizer group (`spark-container` in Docker/Helm, `local` when debugging) and one
optimizer, so in practice `optimizingQueueByGroup` holds one queue and `authOptimizers` one entry.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `FusionServiceContainer` | Process entry point and lifecycle: config loading, single-server startup loop, HTTP server, Thrift servers, wiring of all managers and services. Also holds the static Spark settings used by telemetry (`getSparkConfig`, `updateSparkConfig`). | `DefaultCatalogManager`, `DefaultTableManager`, `DefaultOptimizerManager`, `TerminalManager`, `DefaultTableService`, `DefaultOptimizingService`, `ProcessService`, `InlineTableExecutors`, `OptimizerBootstrap`, `DashboardServer`, `RestCatalogService`, `Containers` |
| `FusionManagementConf` | All server `ConfigOption`s (keys, types, defaults) plus constant key names for `containers`, database types and terminal backends. | Read everywhere via `Configurations` |
| `FusionManagementConfValidator` | Fails startup on invalid config: `server-expose-host` empty, missing DB username/password for postgres/mysql, bad `terminal.backend` (only `local` or `custom`), thread counts `<= 0`. Resolves `server-expose-host` to an IP via `AmsUtil.lookForBindHost`. | `FusionServiceContainer.ConfigurationHelper` |
| `ServerConfigLoader` | Returns the server section of `config.yaml`: root key `fusion:` if present, else the legacy `ams:` key. | `FusionServiceContainer.ConfigurationHelper#initServiceConfig` |
| `Environments` | Resolves home, config and plugin directories from `FUSION_HOME` / `FUSION_CONF_DIR` / `FUSION_PLUGIN_DIR`, falling back to legacy `ARCTIC_HOME`, `AMORO_HOME`, `AMORO_CONF_DIR`, `AMORO_PLUGIN_DIR`, then system properties, then `user.dir`. | `FusionServiceContainer`, `AbstractPluginManager` |
| `DefaultOptimizingService` | Implements `OptimizingService.Iface` (Thrift) and `QuotaProvider`. Authenticates optimizers, routes `pollTask`/`ackTask`/`completeTask` to the group's `OptimizingQueue`, expires silent optimizers, retries stuck tasks, keeps queues in sync with the `resource_group` table, and binds tables to queues on table events. | `OptimizingQueue`, `OptimizerMapper`, `ResourceMapper`, `TableProcessMapper`, `OptimizerManager`, `TableService`, `Telemetry` |
| `TableManagementService` | Implements `AmoroTableMetastore.Iface`: catalog/database/table metadata for internal catalogs and table blockers. `tableCommit` is a no-op; `allocateTransactionId` throws `UnsupportedOperationException`. | `CatalogManager`, `TableManager`, `InternalTableUtil` |
| `RestCatalogService` | Iceberg REST catalog API under `/api/iceberg/rest`, for catalogs of type `ams` (`InternalCatalog`) only. | `CatalogManager`, `InternalTableManager`, `EventsManager` |
| `AmsServiceMetrics` | Registers JVM and HA-state gauges in the global metric registry. | `MetricManager`, `FusionServiceContainer#getHaState` |
| `FusionServiceConstants` | `INVALID_TIME = 0` and `QUOTA_LOOK_BACK_TIME` = 1 hour (window used for quota accounting). | `DefaultTableRuntime`, `OptimizingQueue`, `OptimizingUtil`, `TableOptimizingMetrics` |

## How it works

### Startup

1. `FusionServiceContainer()` calls `initConfig`, which runs `ConfigurationHelper#init`:
   - `initEnvConfig`: `EnvConfig.fromEnv(System.getenv())` turns `FUSION_*` and legacy `AMS_*`
     variables into config keys (only the key names are logged).
   - `initServiceConfig`: reads `Environments.getConfigPath() + "/config.yaml"`, takes the server
     section with `ServerConfigLoader.systemConfig`, flattens nested maps with `expandConfigMap`
     (`a: {b: 1}` becomes `a.b`), overlays env values (env wins), decrypts with
     `ConfigShadeUtils.decryptConfig`, builds `Configurations`, validates with
     `FusionManagementConfValidator.validateConfig`, then creates the data source
     (`DataSourceFactory.createDataSource`) and initialises `SqlSessionFactoryProvider`.
   - `setIcebergSystemProperties`: sets Iceberg's worker pool size
     (`SystemProperties.WORKER_THREAD_POOL_SIZE_PROP`) and `IcebergThreadPools.init` for planning
     and commit pools; each is `max(cores / 2, configured value)`.
   - `initContainerConfig`: reads the top-level `containers:` list into `ContainerMetadata`, adds
     `ams-home` and (if absent) `ams-optimizing-uri` to each container's properties, and calls
     `Containers.init`. If a container's `container-impl` is
     `io.olake.fusion.server.manager.SparkOptimizerContainer`, its `spark-conf.*` driver/executor
     settings seed `SPARK_CONFIG`.
   - `Telemetry.configure(telemetry.disabled)`.
2. `main` registers a shutdown hook that calls `dispose()`, then `startRestServices()`:
   `EventsManager.getInstance()`, `MetricManager.getInstance()`, creates `DefaultCatalogManager`,
   `DefaultTableManager`, `DefaultOptimizerManager`, `TerminalManager`, then `initHttpService` and
   `startHttpService` (Javalin on `http-server.bind-port`) and `registerAmsServiceMetric`.
   The REST API is up before the optimizing service starts.
3. Startup loop: Fusion runs a single server, so `main` calls `transitionToLeader` and then waits
   on a `CountDownLatch` that is never released, with `transitionToFollower` in `finally`. A
   `ConfigurationException` exits the process; any other startup exception is logged, the
   optimizing service is disposed, and the loop retries. `HAState` (`INITIALIZING`, `FOLLOWER`,
   `LEADER`) and `getHaState()` remain because the `ams_ha_state` metric and the
   `/api/ams/v1/health/status` response report it.
4. `transitionToLeader` calls `startOptimizingService`:
   1. `TableRuntimeFactoryManager#initialize`, then `DefaultTableService`,
      `DefaultOptimizingService`, `ProcessService`.
   2. `InlineTableExecutors.getInstance().setup(...)`, then `addHandlerChain` for, in order: the
      optimizing service's `TableRuntimeHandlerImpl`, the process service chain, and the data
      expiring, snapshots expiring, orphan files cleaning, dangling delete files cleaning,
      optimizing commit, optimizing expiring, blocker expiring, table refreshing, tags auto
      creating and table health score executors (each only if not `null`).
   3. `tableService.initialize()`. This runs each chain's `initHandler`; for the optimizing
      service that is `loadOptimizingQueues` (one `OptimizingQueue` per row of `resource_group`,
      re-registers every row of `optimizer`), then starts `OptimizerKeeper` and
      `OptimizingConfigWatcher`.
   4. `tableManager.setTableService(tableService)`.
   5. `new OptimizerBootstrap(...).run()` (see [`bootstrap/`](bootstrap/README.md)).
   6. `initThriftService` and `startThriftService`.

### HTTP server

`initHttpService` builds one Javalin app with the routes of `DashboardServer#endpoints` (all
`/api/ams/v1/...` paths, see `dashboard/`) and `RestCatalogService#endpoints`. A `before` handler
checks single-page tokens (`token` query param, `CommonUtil.checkSinglePageToken`) or delegates to
`DashboardServer#preHandleRequest` (authentication). Exceptions on `/api/iceberg/rest` paths go to
`RestCatalogService#handleException`; all others to `DashboardServer#handleException`. Sessions are
stored through `HttpSessionHandlerFactory.createSessionHandler(dataSource, serviceConfig)`.

### Thrift servers

`initThriftService` creates two `THsHaServer`s with framed transport and binary protocol, each
behind a `TMultiplexedProcessor`:

- `TableMetastore` on `thrift-server.table-service.bind-port`, `TableManagementService` wrapped by
  `ThriftServiceProxy` with `FusionRuntimeException::normalizeCompatibly`, fixed pool of
  `thrift-server.table-service.worker-thread-count` threads.
- `OptimizeManager` on `thrift-server.optimizing-service.bind-port`, `DefaultOptimizingService`
  wrapped with `FusionRuntimeException::normalize`, cached (unbounded) thread pool.

Each `serve()` runs on a daemon thread (`thrift-table-management-server-thread`,
`thrift-optimizing-server-thread`).

### Optimizer protocol (`DefaultOptimizingService`)

- `authenticate(OptimizerRegisterInfo)`:
  1. Rejects with `ForbiddenException` if the optimizer's `heart-beat-interval` property is
     `>=` `optimizer.heart-beat-timeout`.
  2. Looks up the queue for the group (`ObjectNotExistsException` if there is none).
  3. `retireUntaggedRegistrations`: an optimizer without the `optimizer-tag` property is refused
     (`ForbiddenException`, the optimizer then exits) when a tagged one is registered in the group;
     a tagged optimizer unregisters all untagged ones in its group.
  4. `retirePreviousRegistrations(resourceId)`: unregisters every `optimizer` row with the same
     resource id, so an optimizer that Kubernetes restarted does not hold two registrations.
  5. Creates an `OptimizerInstance` (new random UUID token), `registerOptimizer(optimizer, true)`
     inserts it into `optimizer`, adds it to the queue, the token maps and the keeper, calls
     `FusionServiceContainer.updateSparkConfig` and `trackInstalledFusion` (once per process).
     Returns the token.
- `touch(token)`: updates the in-memory touch time and `OptimizerMapper#updateTouchTime`. An
  unknown token throws `PluginRetryAuthException`, which tells the optimizer to authenticate again.
- `pollTask(token, threadId)`: `OptimizingQueue#pollTask(thread, optimizer.polling-timeout,
  self-optimizing.break-quota-limit-enabled)`; returns `TaskRuntime#extractProtocolTask` or `null`.
- `ackTask` / `completeTask`: forwarded to `OptimizingQueue#ackTask` / `#completeTask`.
- `cancelProcess(processId)`: loads `TableProcessMeta`, finds the table runtime, and if its current
  `OptimizingProcess` has that id calls `process.close(true)`. Called through the Thrift client by
  `TableController` (cancel of a Run in the UI).

### Table events (`TableRuntimeHandlerImpl`)

- `handleStatusChanged`: when the new status is `IDLE` or `PENDING`, `OptimizingQueue#refreshTable`
  re-binds the table (planning is skipped for `PLANNING` to avoid churn).
- `handleConfigChanged`: if the table's optimizer group changed, `releaseTable` from the old queue;
  then `refreshTable` on the current one.
- `handleTableAdded` / `handleTableRemoved`: `refreshTable` / `releaseTable`.

`refreshTable` only adds a table whose optimizing is enabled and whose status is not processing.

### Expiry and retry (`OptimizerKeeper`)

Every registered optimizer has an `OptimizerKeepingTask` in a `DelayQueue`, due at
`lastTouchTime + optimizer.heart-beat-timeout`. When one is due the keeper:

1. Collects tasks of that optimizer's queue that match `buildSuspendingPredication`: owned by a
   token that is no longer registered and not `SUCCESS`; or `SCHEDULED` for longer than
   `optimizer.task-ack-timeout`; or `ACKED` for longer than `optimizer.task-execute-timeout`.
   Each goes to `OptimizingQueue#retryTask` (an `IllegalTaskStateException` is logged and retried
   next round).
2. If the optimizer has not touched since the task was queued (or was replaced), `unregisterOptimizer`
   (deletes the `optimizer` row, removes it from the queue); otherwise re-queues it.

### Group sync (`OptimizingConfigWatcher`)

Runs once at init, then every `self-optimizing.refresh-group-interval` on
`resource-group-watcher-%d`. `syncGroups` reads `OptimizerManager#listResourceGroups` and creates,
updates (`OptimizingQueue#updateOptimizerGroup`) or deletes (`OptimizingQueue#dispose`) queues to
match.

### Shutdown and demotion

`transitionToFollower` -> `disposeOptimizingService`: stops both Thrift servers, then
`tableService.dispose()`, `optimizingService.dispose()` (plan executor, watcher, queues, keeper,
handler chain, maps), `processService.dispose()`. `dispose()` (shutdown hook) also runs
`disposeRestService`: closes Javalin, `TerminalManager#dispose`, unregisters `AmsServiceMetrics`,
`EventsManager.dispose()`, `MetricManager.dispose()`.

## State, threads and concurrency

- Threads: `plan-executor-thread-%d` (cached pool shared by all queues for planning),
  `optimizer-keeper-thread` (daemon), `resource-group-watcher-%d`, Thrift selector threads and
  worker pools (`thrift-server-table-metastore-%d`, `thrift-server-optimize-manager-%d`), the two
  `serve()` threads, Javalin/Jetty threads, and the shutdown hook thread.
- In memory, in `DefaultOptimizingService`: `optimizingQueueByGroup`, `optimizingQueueByToken`,
  `authOptimizers` (all `ConcurrentHashMap`), `fusionInstallTracked`. Rebuilt from Postgres on
  each `initHandler`.
- Persisted: `optimizer` (registrations, touch time), `resource_group` (groups), process rows read
  by `cancelProcess` through `TableProcessMapper`.
- Static: `FusionServiceContainer.SPARK_CONFIG` (volatile, replaced on Spark optimizer
  registration), `IS_MASTER_SLAVE_MODE`.
- `haState` is a plain field changed only from the main thread; `AmsServiceMetrics` reads it from
  the metric reporter's thread.
- Thrift calls arrive concurrently on the optimizing worker pool; per-task state changes are
  serialised inside `OptimizingQueue`/`TaskRuntime`, not here.

## Contracts

- **Thrift** (`fusion-core` IDL): service `OptimizeManager` (`OptimizingService`): `ping`, `touch`,
  `pollTask`, `ackTask`, `completeTask`, `authenticate`, `cancelProcess`. Service `TableMetastore`
  (`AmoroTableMetastore`). The optimizer depends on `PluginRetryAuthException` meaning
  "re-authenticate" and on `ForbiddenException` at authentication meaning "stop".
- **REST**: Iceberg REST catalog under `/api/iceberg/rest/v1/...` (`config`, namespaces, tables,
  `tables/rename` -> 406, `.../metrics`). Errors map to codes in `IcebergRestErrorCode`
  (`UnsupportedOperationException` -> 406, not-found -> 404, `CommitFailedException` /
  `AlreadyExistsException` -> 409, else 500). Only `ams`-type catalogs; OLake-UI's catalogs are
  external (`glue`, `rest`, `hive`, `custom`), so OLake-UI does not use this API.
- **Config** (`config.yaml`, under `fusion:` or legacy `ams:`; env `FUSION_<KEY>` or `AMS_<KEY>`,
  where `__` becomes `-` and `_` becomes `.`, for example `AMS_SERVER__EXPOSE__HOST`). The keys
  this package reads directly, with code defaults:
  - `server-bind-host` (`0.0.0.0`), `server-expose-host` (empty, must be set).
  - `http-server.bind-port` (`19090`; deployments set `1630`).
  - `thrift-server.table-service.bind-port` (`1260`), `thrift-server.optimizing-service.bind-port`
    (`1261`), `thrift-server.max-message-size` (`100MB`), `thrift-server.selector-thread-count`
    (`2`), `thrift-server.selector-queue-size` (`4`),
    `thrift-server.table-service.worker-thread-count` (`20`).
  - `optimizer.heart-beat-timeout` (`1min`), `optimizer.task-ack-timeout` (`30s`),
    `optimizer.task-execute-timeout` (`Integer.MAX_VALUE` seconds), `optimizer.polling-timeout`
    (`3s`), `optimizer.max-planning-parallelism` (`1`), `optimizer.bootstrap.group-name` (none).
  - `self-optimizing.refresh-group-interval` (`30s`), `self-optimizing.break-quota-limit-enabled`
    (`true`), `self-optimizing.plan-manifest-io-thread-count` (`10`),
    `self-optimizing.commit-manifest-io-thread-count` (`10`), `table-manifest-io.thread-count`
    (`20`).
  - `telemetry.disabled` (`false`), `database.*`
    (`database.type` defaults to `postgres`).
  - Top-level `containers:` list (`name`, `container-impl`, `properties`), read outside the server
    section.
  Other keys in `FusionManagementConf` are read by the packages that own the feature.
- **Metrics**: `ams_jvm_cpu_load`, `ams_jvm_cpu_time`, `ams_jvm_memory_heap_used`,
  `ams_jvm_memory_heap_committed`, `ams_jvm_memory_heap_max`, `ams_jvm_threads_count`,
  `ams_jvm_garbage_collector_count` and `ams_jvm_garbage_collector_time` (tag `garbage_collector`),
  `ams_ha_state` (tag `service_state` = expose host; value 0/1/2 for INITIALIZING/FOLLOWER/LEADER).
- **Postgres**: `optimizer`, `resource_group`, and process meta via `TableProcessMapper`.
- **Telemetry**: optimizer registration triggers `Installed Fusion` (see [`utils/`](utils/README.md)).

## Extending or changing it

- New server config: add a `ConfigOption` to `FusionManagementConf` (copy an existing one, for
  example `OPTIMIZER_POLLING_TIMEOUT`), and, if it must be validated, a check in
  `FusionManagementConfValidator`. Then check OLake-Helm and OLake-UI's `docker-compose-v1.yml` /
  downloaded `config.yaml` for where it should be set.
- New table-level background job: add the executor to `InlineTableExecutors` and an
  `addHandlerChain(...)` line in `startOptimizingService`.
- New Thrift method: change the IDL in `fusion-core`, implement it in `DefaultOptimizingService`;
  the optimizer side must be updated in the same release.
- Do not change casually: the order in `startOptimizingService` (queues are loaded by
  `tableService.initialize()`, which must happen before `OptimizerBootstrap` and before the Thrift
  server accepts optimizers), the authentication rules (tag and resource-id retirement), and the
  suspend predicate in `OptimizerKeeper`; these decide when a task is retried and can double-run or
  strand work.

## Invariants and pitfalls

- One live registration per optimizer: `retirePreviousRegistrations` relies on the optimizer
  reporting the same resource id after a restart. An optimizer with a blank resource id is never
  retired this way; it only expires through the keeper.
- A tagged optimizer evicts untagged ones in its group; an untagged one cannot join once a tagged
  one is present. Mixing old and new optimizer images in one group will fail authentication.
- Tasks of an optimizer that expired are only re-queued when the keeper wakes for some optimizer of
  that queue. The retry check is driven by keeper wake-ups (about once per heartbeat timeout per
  optimizer), not by a separate timer.
- `optimizer.task-execute-timeout` defaults to effectively infinite; a hung but heartbeating
  optimizer keeps its `ACKED` task forever unless this is set (local-test sets `1h`).
- `loadOptimizingQueues` only binds tables whose group already exists in `resource_group`; others
  are logged as `Unloaded task runtime in group ...`. A queue created later (by
  `OptimizerBootstrap` or the watcher) starts empty and picks tables up on their next
  `handleStatusChanged` (IDLE/PENDING) or `handleConfigChanged`.
- `deleteResourceGroup` in `DefaultOptimizingService` assumes the queue exists (`NullPointerException`
  otherwise); it is only called from `syncGroups` with known names.
- `DefaultOptimizingService#listTasks` and `#deleteOptimizer` have no callers in `src/main`.
- `processService` is not set to `null` in `disposeOptimizingService`; a second dispose calls
  `ProcessService#dispose` again.
- `FusionManagementConf` still defines out-of-scope keys and constants: LDAP
  (`http-server.login-auth-ldap-*`, read by no provider) and `DB_TYPE_DERBY` / `DB_TYPE_MYSQL`.
  They are out of Fusion's scope. `CATALOG_HIVE_SITE` is in use: it names the `hive-site` file of
  Hive Metastore catalogs for Iceberg tables.
  `METRIC_REPORTERS` (`"metric-reports"`) and `EVENT_LISTENERS` are unused; the plugin managers use
  their own category names.
- `RestCatalogService#createTable` always creates `ICEBERG` tables and rejects a request whose
  properties mark it as the base store of a legacy `MIXED_ICEBERG` table
  (`TablePropertyUtil.LEGACY_MIXED_ICEBERG_FORMAT`).
- The startup banner still prints the Amoro logo and URL.

## Tests

In `fusion-ams/src/test/java/io/olake/fusion/server/`:

- `TestDefaultOptimizingService`: poll/ack/complete, touch and touch timeout, task execute timeout
  and retry, reboot and reload of scheduled/acked/completed/failed tasks.
- `TestFusionConfigLoading` (`fusion:` vs `ams:` root), `TestEnvironments`,
  `TestFusionManagementConfValidator`, `TestAmoroManagementConfValidator`,
  `TestAmoroManagementConf`, `TestAmsServiceMetrics`.
- `TestInternalIcebergCatalogService`, `RestCatalogServiceTestBase` (Iceberg REST catalog).
- `TestIcebergOnlyClasspath`: no mixed-format, Hive-table, Paimon or Hudi catalog classes are on
  the server classpath.
- `AMSServiceTestBase`, `AMSManagerTestBase`, `AmsEnvironment` are shared fixtures.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestDefaultOptimizingService -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`bootstrap/`](bootstrap/README.md): creates the optimizer group at leader start.
- [`manager/`](manager/README.md): plugin managers and optimizer containers.
- [`resource/`](resource/README.md): optimizer groups, optimizer registrations, container registry.
- [`events/`](events/README.md): event listener plugins.
- [`utils/`](utils/README.md): Thrift proxy, env config, telemetry, Iceberg helpers.
- [`optimizing/`](optimizing/README.md): `OptimizingQueue`, `TaskRuntime`, planning and commit.
- [`table/`](table/README.md), [`scheduler/`](scheduler/README.md),
  [`process/`](process/README.md), [`catalog/`](catalog/README.md),
  [`dashboard/`](dashboard/README.md), [`persistence/`](persistence/README.md),
  [`terminal/`](terminal/README.md), [`authentication/`](authentication/README.md).
