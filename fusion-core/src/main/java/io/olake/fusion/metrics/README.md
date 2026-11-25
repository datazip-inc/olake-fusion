# `io.olake.fusion.metrics`

A small metrics API: metric definitions with tags, counters and gauges, a registry, and the plugin interface for reporters.

## Role in Fusion

The server registers table, optimizer-group and service metrics in the global registry of `fusion-ams` `io.olake.fusion.server.manager.MetricManager` (plugin category `metric-reporters`). Users: `AmsServiceMetrics`, `TableOptimizingMetrics`, `TableSummaryMetrics`, `TableOrphanFilesCleaningMetrics`, `OptimizerGroupMetrics`. Reporters such as `fusion-metrics/fusion-metrics-prometheus` `PrometheusMetricsReporter` read the registry. The shipped `conf/plugins/metric-reporters.yaml` enables no reporter.

Runs in: **server** only (no optimizer module imports this package).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `MetricDefine` | Name, tag names, `MetricType`, description; built with `defineCounter(name)` / `defineGauge(name)`.`withTags(...)`.`withDescription(...)`.`build()`. Equality by `toString()` (name, type, sorted tags). | — |
| `MetricKey` | A define plus tag values; constructor checks every defined tag has a value. | `MetricDefine` |
| `MetricRegistry` | `register(define, tags, metric)` → `MetricKey`; `unregister(key)`; `getMetrics()`; notifies `MetricRegisterListener`s. Rejects a second define with the same name but different shape, and a duplicate key. | `MetricSet` |
| `Counter` | `LongAdder`-based `inc()`, `inc(n)`, `getCount()`. | — |
| `Gauge<T extends Number>` | `getValue()`; functional interface. | — |
| `MetricReporter` | `ActivePlugin` with `setGlobalMetricSet(MetricSet)`. | `MetricManager` |
| `Metric`, `MetricSet`, `MetricType`, `MetricRegisterListener` | Marker, read view, type check, listener. | — |

## State, threads and concurrency

`MetricRegistry` uses concurrent maps and a `CopyOnWriteArrayList` of listeners. Registration is two separate map operations (`computeIfAbsent` then `computeIfPresent`); a concurrent `unregister` of the last metric of a define between them makes `register` return a key without storing the metric.

## Contracts

Metric names and tag names are what Prometheus scrapes and what dashboards (`grafana/` in the repo) query. Renaming a define is a breaking change for those dashboards.

## Extending or changing it

Define metrics as constants (`MetricDefine.defineGauge(...)...build()`), register them per table in the table's metrics class, and unregister them when the table is removed, or the registry keeps stale keys.

## Invariants and pitfalls

`MetricKey.toString()` drops the metric name when tags exist (it assigns instead of appends); do not rely on it in logs.

## Tests

`fusion-core/src/test/java/io/olake/fusion/metrics/`: `TestMetricRegistry`, `TestMetricKey`, `TestMetricDefine`, `MetricDefineTest`.

```shell
./mvnw -o -Pskip-dashboard-build -Drat.skip=true -Dspotless.check.skip=true test -pl fusion-core -am -Dtest=TestMetricRegistry -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`../table`](../table/README.md) (`TableSummary`, source of summary metrics)
