# `io.olake.fusion.client`

Thrift client plumbing for calling the Fusion server: `thrift://` URL parsing, a pooled thrift
client with one-shot proxies, the JVM-wide cache of `OptimizingService` clients, and a generic
`ClientPool` copied from Iceberg.

## Role in Fusion

Runs mainly in the **optimizer**. The Spark optimizer (and the local standalone optimizer) reach the
server's `OptimizingService` through `OptimizingClientPools#getClient`, called from
`fusion-optimizer-common`'s `AbstractOptimizerOperator#callAms` and `#callAuthenticatedAms`.

The **server** uses it too: `server.dashboard.controller.TableController` calls
`OptimizingClientPools#getClient` on its own optimizing-service address to call `cancelProcess`
(cancel an optimizing process from the REST API).

`ClientPool` / `ClientPoolImpl` are the base of `hive.AuthenticatedHiveClientPool`; no production
code builds such a pool (the `hive` package is kept for test infrastructure).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `OptimizingClientPools` | Static Caffeine `LoadingCache` from URL to `ThriftClientPool<OptimizingService.Client>`. `getClient(url)` returns a one-shot `OptimizingService.Iface`. | `ThriftClientPool`, `PoolConfig`, `Constants.THRIFT_OPTIMIZING_SERVICE_NAME` |
| `ThriftClientPool<T>` | Commons-pool2 `GenericObjectPool` of `ThriftClient`s for one URL. Opens framed transports, reconnects, validates by ping, and hands out proxies via `iface()`. | `AmsThriftUrl`, `PoolConfig`, `ThriftClientFactory`, `ThriftPingFactory` |
| `ThriftClient<T>` | Pooled wrapper of a `TServiceClient` plus its `ServiceInfo`; `isDisConnected()`, `finish()`, `close()` (return or invalidate). | `ThriftUtil#closeClient` |
| `PoolConfig<T>` | `GenericObjectPoolConfig` plus `connectTimeout`, `socketTimeout`, `maxMessageSize` (100 MB), `autoReconnect` (true), `maxReconnects` (5). `forUrl(url)` sets fields from URL query parameters with `BeanUtils`. | `ThriftClientPool` |
| `AmsThriftUrl` | Parses `thrift://host:port/catalog?socketTimeout=..` into schema, host, port, catalog name and socket timeout; `serverUrl()` rebuilds `thrift://host:port/`. | `ThriftClientPool` |
| `ServiceInfo` | Mutable host and port of the backend a client is connected to. | `ThriftClient`, `ThriftClientPool` |
| `ThriftClientFactory`, `ThriftPingFactory` | Functional interfaces: build a `TServiceClient` from a transport; ping a client. | `ThriftClientPool` |
| `ThriftUtil` | `closeClient` closes the input and output transports, logging failures. | `ThriftClient` |
| `ThriftException`, `ConnectionFailException`, `NoBackendServiceException` | Unchecked errors of the pool: generic, connect failed after retries, no backend. | `ThriftClientPool` |
| `ClientPool<C, E>`, `ClientPoolImpl<C, E>` | Generic bounded client pool copied from Iceberg (`run(action)`, `run(action, retry)`, reconnect on a connection exception). | `hive.AuthenticatedHiveClientPool` |

## How it works

**URL to pool.** `OptimizingClientPools#getClient(url)` loads or creates a pool for the exact URL
string. `buildClient` takes `PoolConfig.forUrl(url)`, sets `maxTotal` to `-1` (no upper bound), and
builds clients as `new OptimizingService.Client(new TMultiplexedProtocol(new TBinaryProtocol(s),
"OptimizeManager"))`. The ping factory calls `OptimizingService.Client#ping`.

**Creating a connection.** The pool's `create()` calls `AmsThriftUrl.parse(url, serviceName)` for
every new connection. The transport is
`TFramedTransport(TSocket(host, port, connectTimeout, socketTimeout), maxMessageSize)`. If `open()`
fails and `autoReconnect` is on, it re-parses the URL and retries up to `maxReconnects` times,
sleeping 2 s between attempts, then throws `ConnectionFailException`.

**Borrowing.** `ThriftClientPool#iface()` borrows up to 5 times. A borrowed client that is
disconnected or fails ping is invalidated and the whole pool is cleared; from the third attempt it
waits 2 s first ("Maybe server is restarting"). After 5 failures it throws
`ThriftException("Client can not connect.")`. Borrow and return are tested (`testOnBorrow`,
`testOnReturn` set to true).

**One-shot proxy.** `iface()` returns a JDK proxy over the client's interfaces. The first method
call runs on the borrowed client; on success the client goes back to the pool, on any exception it is
closed and invalidated. A second call on the same proxy throws `IllegalStateException`. Callers must
call `getClient` again for every thrift call, which is what `AbstractOptimizerOperator` does.

**URL parsing.** `AmsThriftUrl#parse` throws `IllegalArgumentException` for a null URL and
otherwise lower-cases the whole URL, parses it as a `java.net.URI`, takes the path (without the
leading `/`) as the catalog name and reads `socketTimeout` from the query (default
`DEFAULT_SOCKET_TIMEOUT = 5000`). The `serviceName` argument is not used, and the scheme is not
checked.

**Server side of the address.** The server builds the optimizing-service URL in
`server.dashboard.utils.AmsUtil#getAMSThriftAddress`:
`thrift://<server-expose-host>:<thrift-server.optimizing-service.bind-port>` (default port 1261).

## State, threads and concurrency

- `OptimizingClientPools.CLIENT_POOLS` is static: one pool per URL per JVM, never evicted. In Spark,
  that is one per driver or executor JVM.
- `ThriftClientPool` is thread safe (commons-pool2). A proxy from `iface()` must be used by one
  thread for one call.
- `ClientPoolImpl` uses `synchronized (this)` for the deque and a separate `signal` monitor with 1 s
  waits; `close()` waits until all clients are returned.

## Contracts

- Thrift: `OptimizingService` (`amoro_optimizing_service.thrift`) over `TFramedTransport` +
  `TBinaryProtocol` + `TMultiplexedProtocol` with service name `Constants.THRIFT_OPTIMIZING_SERVICE_NAME`
  (`"OptimizeManager"`). The server must register the processor under the same name.
- URL query parameters recognised by `PoolConfig.forUrl`: any writable bean property of `PoolConfig`
  and `GenericObjectPoolConfig`, for example `socketTimeout`, `connectTimeout`, `maxMessageSize`,
  `autoReconnect`, `maxReconnects`, `maxIdle`, `minIdle`.

## Extending or changing it

- A client for another thrift service: follow `OptimizingClientPools#buildClient` (new
  `ThriftClientPool` with a `TMultiplexedProtocol` using the service's name from `Constants` and a
  ping lambda).
- New pool tuning: add a field with getter and setter to `PoolConfig`; it becomes a URL query
  parameter automatically.

## Invariants and pitfalls

- **Server/optimizer compatibility.** The optimizer image carries its own copy of this package.
  Transport (framed + binary + multiplexed), the service name and `maxMessageSize` must stay
  compatible with the server's `FusionServiceContainer#initThriftService`
  (`thrift-server.max-message-size`). Task inputs larger than the client's 100 MB `maxMessageSize`
  fail on the client side.
- `PoolConfig` defaults `connectTimeout` and `socketTimeout` to `0` (no timeout) unless the URL sets
  them. `AmsThriftUrl#socketTimeout()` is parsed but not used by `ThriftClientPool`.
- `ThriftClientPool#getTransport` builds a `TConfiguration` with the max message size but passes a
  fresh `new TConfiguration()` to the `TSocket`; the limit applies only through the
  `TFramedTransport` frame size.
- `AmsThriftUrl` lower-cases URLs, including the catalog path.
- The one-shot proxy invalidates the connection on any exception, including thrift
  `AmoroException`s that are normal business errors.

## Tests

`fusion-core/src/test/java/io/olake/fusion/client/TestPoolConfig.java` covers `PoolConfig.forUrl`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am \
  -Dtest=TestPoolConfig -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`io.olake.fusion`](../README.md) (`Constants`, `ErrorCodes`, `AmsClient`)
- [`exception`](../exception/README.md) (how server errors become `AmoroException`)
- [`hive`](../hive/README.md) (uses `ClientPoolImpl`; test infrastructure only)
- `fusion-optimizer/fusion-optimizer-common` (`AbstractOptimizerOperator`), `fusion-ams`
  `server.dashboard.utils.AmsUtil`
