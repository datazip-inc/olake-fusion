# `io.olake.fusion.server.bootstrap`

Makes sure Fusion's single optimizer group exists, as an external group, every time a node
becomes leader.

## Role in Fusion

Server side. `FusionServiceContainer#startOptimizingService` runs `OptimizerBootstrap#run` after
`tableService.initialize()` and before the Thrift servers start. In Docker and Helm deployments the
Spark optimizer is deployed and restarted by Kubernetes, not by Fusion; it registers itself into the
group named by `optimizer.bootstrap.group-name` (`spark-container`, OLake-UI's
`OPTIMIZATION_GROUP`). This package only makes that group exist so the optimizer's `authenticate`
call finds a queue.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizerBootstrap` | Creates or fixes the configured optimizer group in Postgres and in the in-memory queues. Does not start or stop optimizers. | `OptimizerManager` (`DefaultOptimizerManager`), `DefaultOptimizingService`, `ResourceGroup.Builder` |

## How it works

`OptimizerBootstrap#run`:

1. Reads `optimizer.bootstrap.group-name`. If blank, logs
   `Optimizer bootstrap is not configured (group-name missing), skipping.` and returns.
2. Builds the target group with the one-argument `new ResourceGroup.Builder(name).build()`, whose
   container is `Constants.EXTERNAL_RESOURCE_CONTAINER` (`"external"`). An external group accepts
   optimizers that Fusion did not start.
3. `optimizerManager.getResourceGroup(name)`:
   - `null`: `optimizerManager.createResourceGroup` (insert into `resource_group`) and
     `optimizingService.createResourceGroup` (new `OptimizingQueue`).
   - exists with a different container (a group left over from a setup where the server launched
     the optimizer itself): `optimizerManager.updateResourceGroup` and
     `optimizingService.updateResourceGroup`.
   - exists and already external: nothing.

## State, threads and concurrency

Runs once per leader transition on the main thread. No threads or in-memory state of its own; the
result is a `resource_group` row plus an `OptimizingQueue` inside `DefaultOptimizingService`.

## Contracts

- Config key `optimizer.bootstrap.group-name` (`FusionManagementConf.OPTIMIZER_BOOTSTRAP_GROUP_NAME`,
  no default). `local-test/config.yaml` sets `spark-container`.
- Postgres table `resource_group` (via `ResourceMapper`): the group row with `container_name =
  'external'`.

## Extending or changing it

Keep it idempotent: it runs on every leader start, including after a demotion and re-election. If
a group property must be set for the optimizer (for example quotas or `spark-conf.*`), add it to the
builder here and compare it in the update branch; today only the container is compared, so changed
properties on an existing group are not touched.

## Invariants and pitfalls

- The update branch replaces the group with one that has no properties (the builder sets none), so
  properties stored on a pre-existing non-external group are dropped when it is converted.
- Because the group is external, `Containers.get("external")` has no entry, so creating an optimizer
  for this group through `POST /api/ams/v1/optimize/optimizerGroups/{group}/optimizers` fails with
  `ResourceContainer not found: external` unless a container named `external` is configured. The
  `local` debug group is created separately with `localContainer`.
- Tables in this group that were loaded before the queue existed are logged as unloaded by
  `DefaultOptimizingService#loadOptimizingQueues` and join the new queue on their next status or
  config change (see the root package README).

## Tests

No dedicated test class. Group/queue behaviour is covered indirectly by
`TestDefaultOptimizingService` and `TestOptimizerGroupController` in
`fusion-ams/src/test/java/io/olake/fusion/server/`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am -Dtest=TestDefaultOptimizingService -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`..`](../README.md): `FusionServiceContainer`, `DefaultOptimizingService`.
- [`../resource/`](../resource/README.md): `OptimizerManager`, `DefaultOptimizerManager`.
- [`../optimizing/`](../optimizing/README.md): `OptimizingQueue`.
