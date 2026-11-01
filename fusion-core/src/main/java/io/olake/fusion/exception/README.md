# `io.olake.fusion.exception`

Fusion's exception hierarchy. `FusionRuntimeException` gives each subclass an error code from
`io.olake.fusion.ErrorCodes` and converts any `Throwable` into the thrift exceptions that the server
returns to its clients.

## Role in Fusion

The classes are in `fusion-core`, so both sides have them, but they are **thrown and converted in the
server**:

- `FusionServiceContainer#initThriftService` wraps both thrift services in
  `server.utils.ThriftServiceProxy`. The optimizing service uses
  `FusionRuntimeException::normalize` (every error becomes `AmoroException` with a code); the table
  metastore service uses `FusionRuntimeException::normalizeCompatibly` (classic thrift exceptions).
- `server.persistence.PersistentBase`, `DefaultTableRuntimeStore` and `DefaultTableProcessStore` wrap
  database errors with `FusionRuntimeException.wrap(t, PersistenceException::new)`.
- `server.dashboard.DashboardServer#handleException` maps `ForbiddenException` and
  `SignatureCheckException` to REST error responses.
- `process.SimpleFuture` (fusion-core) normalizes errors with `FusionRuntimeException.wrap`.

The **optimizer** receives the result: it sees thrift `AmoroException.errorCode` values, not these
classes, and decides to retry in `AbstractOptimizerOperator`.

## Key classes

| Class | Error code (`ErrorCodes`) | Thrown for |
| --- | --- | --- |
| `FusionRuntimeException` | from `CODE_MAP`, else `-1` | Base class. `getErrorCode()`, `getErrorName()` (simple class name). Static `normalize`, `normalizeCompatibly`, `wrap`. |
| `PersistenceException` | `PERSISTENCE_ERROR_CODE` 1000 | Database (MyBatis/PostgreSQL) failures. |
| `ObjectNotExistsException` | `OBJECT_NOT_EXISTS_ERROR_CODE` 1001 | Missing catalog, table, optimizer, etc.; message `"... not exists"`. |
| `AlreadyExistsException` | `ALREADY_EXISTS_ERROR_CODE` 1002 | Duplicate object; message `"... already exists."`. |
| `IllegalMetadataException` | `ILLEGAL_METADATA_ERROR_CODE` 1003 | Invalid metadata. |
| `ForbiddenException` | `FORBIDDEN_ERROR_CODE` 1004 | No login session (REST); untagged optimizer refused (`DefaultOptimizingService`); auth failures. |
| `TaskNotFoundException` | `TASK_NOT_FOUND_ERROR_CODE` 2001 | Unknown `OptimizingTaskId` (`getTaskId()`). |
| `TaskRuntimeException` | `TASK_RUNTIME_ERROR_CODE` 2002 | Task handling errors; has a `String.format` constructor. |
| `OptimizingClosedException` | `OPTIMIZING_CLOSED_ERROR_CODE` 2003 | Process already closed (`getProcessId()`). |
| `IllegalTaskStateException` | `ILLEGAL_TASK_STATE_ERROR_CODE` 2004 | Invalid task status transition (`getTaskId`, `getPreStatus`, `getTargetStatus`). |
| `PluginAuthException` | `PLUGIN_AUTH_ERROR_CODE` 2005 | Authentication failure. |
| `PluginRetryAuthException` | `PLUGIN_RETRY_AUTH_ERROR_CODE` 2006 | Token no longer valid; the optimizer resets its token and authenticates again. |
| `BlockerConflictException` | `BLOCKER_CONFLICT_ERROR_CODE` 3001 | Table blocker conflict. |
| `UndefinedException` | `-1` (not in map) | Wrapper used by `wrap(Throwable)` for foreign exceptions. |
| `LoadingPluginException` | `-1` (not in map) | Plugin load failures (`AbstractPluginManager`). |
| `SignatureCheckException` | `-1` (not in map) | REST API signature check failure. |
| `OptimizingCommitException` | none | Checked `Exception`, not a `FusionRuntimeException`. `isRetryable()`; used by the server's commit path (`UnKeyedTableCommit`). |

## How it works

- **Code assignment.** Each constructor of `FusionRuntimeException` looks up `getClass()` in the
  static `CODE_MAP`. The lookup is by exact class, so a subclass of a mapped class gets `-1`.
- **`wrap(throwable)`** returns the throwable if it is already a `FusionRuntimeException`, otherwise
  `new UndefinedException(throwable)`. `wrap(throwable, transform)` uses the given factory instead
  (for example `PersistenceException::new`).
- **`normalize(throwable)`** = `wrap(throwable).transform()` →
  `new AmoroException(errorCode, errorName, getMessage())`. Used for `OptimizingService`.
- **`normalizeCompatibly(throwable)`** maps by exact class: `ObjectNotExistsException` →
  `NoSuchObjectException`; `AlreadyExistsException` → thrift `AlreadyExistsException`;
  `IllegalMetadataException` and `PersistenceException` → `MetaException`;
  `IllegalArgumentException` → `InvalidObjectException`; `BlockerConflictException` →
  `OperationConflictException`; anything else → plain `TException(message)`. Used for
  `AmoroTableMetastore`.
- **Optimizer reaction** (`fusion-optimizer-common` `AbstractOptimizerOperator`): an `AmoroException`
  with code 1000 or -1 is retried after a short wait; code 2006 on authenticated calls resets the
  token; other `AmoroException`s are rethrown; any non-`AmoroException` error is retried.
  `OptimizerToucher` exits the process on code 1004 from `authenticate` and drops its token on 2006
  from `touch`.
- **REST.** `DashboardServer#handleException` returns an `ErrorResponse` with `HttpCode.FORBIDDEN`
  for `ForbiddenException` (or `index.html` for page requests outside `/api/ams`) and for
  `SignatureCheckException`; everything else gets `HttpCode.INTERNAL_SERVER_ERROR` with the message.

## State, threads and concurrency

`CODE_MAP` is a static `HashMap` filled in a static initializer and then only read. Exceptions are
immutable.

## Contracts

- Error codes and `errorName` (the simple class name) travel to the optimizer in thrift
  `AmoroException` (`amoro_commons.thrift`). Renaming a class changes `errorName`.
- The exact-class mapping in `normalizeCompatibly` defines which thrift exception type table
  metastore clients see.

## Extending or changing it

New error: add a code to `ErrorCodes`, add a subclass of `FusionRuntimeException`, and add a
`CODE_MAP.put(...)` line. If the table metastore should see a specific thrift type, add a branch in
`normalizeCompatibly`. If the optimizer should retry on it, change
`AbstractOptimizerOperator#shouldRetryLater` too, and remember that the change reaches the optimizer
only with a new optimizer image.

## Invariants and pitfalls

- **Rolling upgrades.** The server may send a code the running optimizer does not know. Unknown codes
  are not retried (only 1000 and -1 are). Do not reuse or renumber codes.
- An unmapped subclass (or a subclass of a mapped class) reports `-1`, which the optimizer retries
  forever while it runs. `LoadingPluginException` and `SignatureCheckException` are unmapped.
- `ForbiddenException` from `authenticate` (code 1004) makes the optimizer's `OptimizerToucher` call
  `System.exit(1)`. This is how an untagged (older) optimizer is shut out. Throwing
  `ForbiddenException` from any other path of `authenticate` also kills the optimizer.
- Two `AlreadyExistsException` classes exist: this one (`FusionRuntimeException`, code 1002) and
  `io.olake.fusion.AlreadyExistsException` (plain `RuntimeException`, no code). A third is the thrift
  `io.olake.fusion.api.AlreadyExistsException`.
- `ThriftServiceProxy` logs every failed call at error level before converting it.

## Tests

`fusion-core` `process/TestSimpleFuture` exercises `wrap`; `fusion-ams`
`TestDefaultOptimizingService` exercises codes returned to optimizers.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-ams -am \
  -Dtest=TestDefaultOptimizingService -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`io.olake.fusion`](../README.md) (`ErrorCodes`)
- [`client`](../client/README.md) (how the optimizer calls the server)
- `fusion-ams` `server.utils.ThriftServiceProxy`, `server.dashboard.DashboardServer`,
  `fusion-optimizer-common` `AbstractOptimizerOperator`
