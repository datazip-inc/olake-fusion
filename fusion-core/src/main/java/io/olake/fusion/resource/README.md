# `io.olake.fusion.resource`

The model for optimizer resources: `ResourceGroup` (an optimizer group), `Resource` (one optimizer
instance), the container interfaces that start and stop optimizers, and `ResourceManager` for
persisting groups and resources.

## Role in Fusion

Mostly **server**: `fusion-ams`'s `DefaultOptimizerManager` (implements `ResourceManager` through
`OptimizerManager`), `server.manager.AbstractOptimizerContainer` (implements
`InternalResourceContainer`), the Spark/Kubernetes/local containers, `OptimizingQueue`,
`DefaultOptimizingService`, `ResourceMapper` (tables `resource` and `resource_group`), and the
optimizer and optimizer-group REST controllers.

The **optimizer** uses only the constant `Resource.PROPERTY_JOB_ID`: `SparkOptimizer` registers with
`job-id` set to the Spark application id, and `StandaloneOptimizer` sets it too.

Fusion has one optimizer group (`spark-container` in deployments, `local` for local debugging) and
one optimizer. The model allows more; do not add code for several.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `ResourceGroup` | Optimizer group: `name`, `container` name, `properties`. Built with `ResourceGroup.Builder(name, container)` or `Builder(name)` (container `Constants.EXTERNAL_RESOURCE_CONTAINER = "external"`). `equals` uses all three fields. | `ResourceMapper`, `OptimizingQueue`, `OptimizerGroupController` |
| `Resource` | One optimizer: `resourceId`, `containerName`, `groupName`, `threadCount`, `memoryMb`, `properties`, `type`. `Builder` generates a short id (random UUID as base-32). A protected constructor builds it from thrift `OptimizerRegisterInfo`. `getRequiredProperty(key)` fails if missing. | `server.resource.OptimizerInstance`, containers |
| `ResourceContainer` | `name()`, `init(name, properties)`, `getStatus(resourceId)`. | `server.manager.Containers` |
| `InternalResourceContainer` | Container that Fusion manages: `requestResource`, `releaseResource`. Resources and processes are decoupled. | `AbstractOptimizerContainer` and its subclasses |
| `ExternalResourceContainer` | Container managed elsewhere: `submit(FusionProcess)` and `release(resourceId)`, one resource per process. No implementation in the tree. | `process.FusionProcess` |
| `ResourceManager` | CRUD on groups and resources: `createResourceGroup`, `updateResourceGroup`, `deleteResourceGroup`, `createResource`, `deleteResource`, `listResourceGroups`, `getResourceGroup`, `listResourcesByGroup`, `getResource`. | `server.resource.DefaultOptimizerManager` |
| `ResourceStatus` | `INITIALIZING`, `RUNNING`, `UNKNOWN`, `NOT_RUNNING`. | `AbstractOptimizerContainer#getStatus` |
| `ResourceType` | One value, `OPTIMIZER(0)`. | REST controllers |

## How it works

Two ways a group and its optimizer come to exist:

- **Bootstrap (deployments).** When `optimizer.bootstrap.group-name` is set, the server's
  `OptimizerBootstrap#run` creates the group with `new ResourceGroup.Builder(name).build()`, that is
  with container `external`, or switches an existing group to `external`. The comment there says
  this lets optimizers that this server did not create register into the group. The Spark optimizer
  then registers itself by calling `authenticate`.
- **Started by the server (local debugging).** A group is created with
  `POST /api/ams/v1/optimize/resourceGroups` (for example container `localContainer`). Starting an
  optimizer (`POST .../optimizerGroups/{group}/optimizers`) builds a `Resource` and
  `OptimizerController` / `OptimizerGroupController` call
  `AbstractOptimizerContainer#requestResource`, which launches the process.

In both cases the optimizer's `OptimizerRegisterInfo` (with its `resource-id`, `job-id` and
`optimizer-tag` properties) is wrapped by the server in an `OptimizerInstance`, a `Resource`
subclass, and stored in the `optimizer` table; groups are stored in `resource_group`.

## State, threads and concurrency

Value objects with no synchronization. `Resource.Builder#setProperties` stores the given map without
copying; `ResourceGroup.getProperties()` returns the internal map.

## Contracts

- `Resource.PROPERTY_JOB_ID = "job-id"` is sent by the optimizer and read by the server's containers;
  it must keep its name across versions.
- Fields map to PostgreSQL columns through `ResourceMapper` (`group_name`, `container_name`,
  `properties`, etc.).
- Group and resource properties use the keys in `io.olake.fusion.OptimizerProperties`.

## Extending or changing it

New field: add it to the class and its builder, then to `ResourceMapper` and the SQL schema
(`fusion-ams/src/main/resources/postgres/`). New container: subclass `AbstractOptimizerContainer`
and register it in `config.yaml` under containers.

## Invariants and pitfalls

- `Resource.Builder` ids are short (base-32) because Kubernetes resource names have length limits.
- An optimizer image that does not send `job-id` cannot be tracked by containers that require it
  (`getRequiredProperty`).

## Tests

`fusion-ams` `TestDefaultOptimizingService`, `TestOptimizerGroupController`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am \
  -Dtest=TestOptimizerGroupController -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`io.olake.fusion`](../README.md) (`OptimizerProperties`, `Constants`)
- `fusion-ams` `server.resource`, `server.manager`, `server.persistence.mapper.ResourceMapper`
