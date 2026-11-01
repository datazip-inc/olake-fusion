# `io.olake.fusion.server.resource`

Server-side model and storage for optimizer groups, optimizer resources and registered optimizer
instances, plus the static registry of configured optimizer containers.

## Role in Fusion

Server side. In Amoro terms an optimizer group is a "resource group", a launched optimizer is a
"resource", and an optimizer that has authenticated is an "optimizer instance".

- `DefaultOptimizerManager` is the Postgres-backed `OptimizerManager`. It is created in
  `FusionServiceContainer#startRestServices` and used by the REST controllers
  (`OptimizerGroupController`, `OptimizerController`, `SettingController`), by
  `OptimizerBootstrap`, and by `DefaultOptimizingService`'s group watcher
  (`listResourceGroups`).
- `OptimizerInstance` and `OptimizerThread` are the in-memory identity of the optimizer that
  `DefaultOptimizingService` and `OptimizingQueue` use for task ownership.
- `QuotaProvider` is how `OptimizingQueue` learns the group's total thread count;
  `DefaultOptimizingService` implements it.
- `Containers` holds the containers from `config.yaml` `containers:`.

Fusion has one group and one optimizer, so these lists normally hold one element each. The
`resource` table is only written when an optimizer is launched through a container (the `local`
debug group); the Spark optimizer in production is started by Kubernetes, appears only in the
`optimizer` table, and its group is external.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizerManager` | Extends `io.olake.fusion.resource.ResourceManager` (group and resource CRUD) with `listOptimizers()`, `listOptimizers(group)`, `deleteOptimizer(group, resourceId)`, `canDeleteResourceGroup(name)`. | |
| `DefaultOptimizerManager` | `PersistentBase` implementation over `OptimizerMapper` (`optimizer` table), `ResourceMapper` (`resource`, `resource_group`) and `TableRuntimeMapper`. Stateless. | `CatalogManager`, mappers |
| `OptimizerInstance` | Extends `Resource`; adds `token` (random UUID made at authentication), `startTime` (from `OptimizerRegisterInfo`), `touchTime`. `touch()` sets `touchTime` to now; `getThread(id)` returns a new `OptimizerThread`. | `DefaultOptimizingService`, `OptimizerMapper` |
| `OptimizerThread` | Value object (`threadId`, owning `OptimizerInstance`); `getToken()` delegates to the optimizer. Equality is by thread id and optimizer. | `OptimizingQueue`, `TaskRuntime` |
| `QuotaProvider` | `int getTotalQuota(String resourceGroup)`. | `OptimizingQueue`, `DefaultOptimizingService` |
| `ContainerMetadata` | Name, implementation class and properties of one configured container. | `FusionServiceContainer`, `Containers` |
| `Containers` | Static registry. `init(list)` (once) instantiates each `container-impl` by reflection (class name passed through `LegacyClassNames.toCurrent`, so old `org.apache.amoro.` names still load) and calls `init(name, properties)`. `get(name)`, `contains(name)`, `getMetadataList()`. | `LegacyClassNames`, `ResourceContainer` |

## How it works

- **Group CRUD**: `createResourceGroup`, `updateResourceGroup` and `deleteResourceGroup` write
  `resource_group`. `deleteResourceGroup` first calls `canDeleteResourceGroup`, which returns
  false if any catalog's `table.self-optimizing.group` property
  (`CatalogMetaProperties.TABLE_PROPERTIES_PREFIX + TableProperties.SELF_OPTIMIZING_GROUP`, treated
  as `default` when unset) names the group, if any registered
  optimizer is in it, or if `TableRuntimeMapper#queryForGroups` returns any table runtime for it;
  otherwise it throws a `RuntimeException`. The in-memory queue follows within
  `self-optimizing.refresh-group-interval` through `DefaultOptimizingService`'s
  `OptimizingConfigWatcher`, or immediately when the caller also calls the optimizing service, as
  `OptimizerBootstrap` does.
- **Resources**: `createResource` / `deleteResource` / `getResource` / `listResourcesByGroup` on
  the `resource` table, used when a container launches or releases an optimizer.
- **Optimizers**: `listOptimizers` reads all `optimizer` rows (filtering by group in memory).
  `deleteOptimizer(group, resourceId)` deletes the `optimizer` rows with that resource id; the
  `group` argument is unused.
- **Quota**: `DefaultOptimizingService#getTotalQuota(group)` sums `getThreadCount()` of the
  authenticated optimizers in the group. `OptimizingQueue` uses `max(total, 1)` as the group's
  available cores when planning and scheduling.
- **Containers**: `FusionServiceContainer.ConfigurationHelper#initContainerConfig` calls
  `Containers.init` once at startup; controllers call `Containers.get(resource.getContainerName())`
  and cast to `AbstractOptimizerContainer`.

## State, threads and concurrency

- `DefaultOptimizerManager` has no in-memory state; every call is a database round trip.
- `OptimizerInstance.touchTime` is a plain `long` written by the Thrift thread handling `touch` and
  read by `optimizer-keeper-thread` in `DefaultOptimizingService`. It is not volatile; the keeper
  tolerates a stale read because a missed touch only delays expiry or causes one extra cycle.
- `Containers.globalContainers` is a plain `HashMap` filled once by `init` (guarded by the volatile
  `isInitialized`) and read-only afterwards.
- Persisted: `optimizer`, `resource`, `resource_group` (see
  `fusion-ams/src/main/resources/postgres/ams-postgres-init.sql`).

## Contracts

- Postgres tables `optimizer` (token, resource_id, group_name, container_name, start_time,
  touch_time, thread_count, total_memory, properties), `resource`, `resource_group` (group_name,
  container_name, properties), through `OptimizerMapper` and `ResourceMapper`.
- REST (served by `dashboard/` controllers on top of `OptimizerManager`):
  `/api/ams/v1/optimize/optimizerGroups`, `/optimizerGroups/{optimizerGroup}/optimizers`,
  `/optimizerGroups/{optimizerGroup}/info`, `/resourceGroups`, `/optimizers`,
  `/optimizers/{jobId}`. OLake-UI reaches the forwarded ones through `/api/opt/v1/...`.
- `ResourceGroup` with container `external` (`Constants.EXTERNAL_RESOURCE_CONTAINER`) means
  optimizers are started outside Fusion.

## Extending or changing it

- Add queries to the mappers in `persistence/mapper/` and expose them on `OptimizerManager`; keep
  `DefaultOptimizerManager` stateless so it stays safe on non-leader nodes (the REST API runs on
  every node).
- Do not cache groups here: `DefaultOptimizingService` already keeps the in-memory view and
  reconciles it with `syncGroups`.

## Invariants and pitfalls

- `DefaultOptimizerManager#deleteOptimizer` only deletes `optimizer` rows. The in-memory
  registration in `DefaultOptimizingService` stays until the keeper expires it; if the optimizer
  is still running and heartbeating, it keeps polling under its old token.
- `listResourceGroups(containerName)` calls `group.getContainer().equals(...)`; a group with a
  `null` container would throw.
- `Containers.init` can be called only once per JVM (`checkState(!isInitialized)`); tests that boot
  several servers in one JVM must account for that.
- `Containers.get("external")` throws `IllegalArgumentException` unless a container with that name
  is configured, so external groups cannot be scaled out through the REST API.
- Neither `OptimizerInstance` nor `Resource` overrides `equals`, so instances compare by
  identity. `OptimizerKeepingTask#tryKeeping` relies on this to tell a re-registered optimizer
  (new object) from the one it was watching, and `OptimizerThread#equals` is only true for threads
  of the same in-memory instance. Instances loaded from the database are new objects.
- Class Javadoc on `DefaultOptimizerManager` is copied from `DefaultOptimizingService` and does not
  describe this class.

## Tests

No test class targets this package directly. `TestDefaultOptimizingService` (registration, quota,
expiry) and `TestOptimizerGroupController` (group REST endpoints) in
`fusion-ams/src/test/java/io/olake/fusion/server/` exercise it, and
`optimizing/TestOptimizingQueue` uses `OptimizerInstance` / `OptimizerThread`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestOptimizerGroupController -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md): `DefaultOptimizingService` (registrations, `QuotaProvider`).
- [`../bootstrap/`](../bootstrap/README.md): creates the Fusion group.
- [`../manager/`](../manager/README.md): the container implementations.
- [`../persistence/`](../persistence/README.md): `OptimizerMapper`, `ResourceMapper`,
  `TableRuntimeMapper`.
- [`../optimizing/`](../optimizing/README.md): `OptimizingQueue`, `TaskRuntime`.
- [`../dashboard/`](../dashboard/README.md): the REST controllers.
