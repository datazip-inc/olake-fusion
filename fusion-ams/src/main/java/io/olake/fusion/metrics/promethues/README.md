# `io.olake.fusion.metrics.promethues`

A metric-reporter plugin for the Fusion server. It serves the server's global metric registry in
Prometheus text format over HTTP. The package name is spelled `promethues` in the code.

## Role in Fusion

- Module `fusion-metrics/fusion-metrics-prometheus`, the only module in `fusion-metrics`. Its
  sources are this package (2 classes) and the service file
  `src/main/resources/META-INF/services/io.olake.fusion.metrics.MetricReporter`, which names
  `io.olake.fusion.metrics.promethues.PrometheusMetricsReporter`.
- It runs in the **server** process only, not in optimizers.
- The `maven-assembly-plugin` builds a `jar-with-dependencies` that includes the Prometheus
  `simpleclient` and `simpleclient_httpserver`. `dist/src/main/assemblies/bin.xml` installs it as
  `plugin/metric-reporters/prometheus-reporter.jar`, and `dist/pom.xml` also depends on the module.
- `io.olake.fusion.server.manager.MetricManager` (plugin category `metric-reporters`) loads it
  through `AbstractPluginManager`, which finds `MetricReporter` implementations with `ServiceLoader`
  on the server classpath and in each jar or directory under `<plugin dir>/metric-reporters`. The
  plugin dir is `FUSION_PLUGIN_DIR` (or the legacy `AMORO_PLUGIN_DIR`), otherwise `<home>/plugin`
  (`io.olake.fusion.server.Environments.getPluginPath`).

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `PrometheusMetricsReporter` | `MetricReporter` named `prometheus-exporter`. `open(properties)` starts `io.prometheus.client.exporter.HTTPServer` on property `port`; `close()` stops it; `setGlobalMetricSet` registers a `MetricsCollector`. | `MetricManager`, `HTTPServer`, `MetricsCollector` |
| `MetricsCollector` | Prometheus `Collector`. On every scrape, `collect()` reads `MetricSet.getMetrics()`, groups keys by `MetricDefine`, and turns each group into one `MetricFamilySamples`. | `io.olake.fusion.metrics.MetricSet`, `MetricDefine`, `MetricKey`, `Counter`, `Gauge` |

## How it works

1. On server start, `MetricManager.initialize()` reads `<conf dir>/plugins/metric-reporters.yaml`,
   discovers the plugins, and for each **enabled** entry calls `install(name)`, which calls
   `open(properties)`.
2. `PrometheusMetricsReporter.open` parses `port` as an integer (missing → `IllegalArgumentException("Lack required property: port")`)
   and starts `new HTTPServer(port)`. A bind failure becomes `RuntimeException("Start prometheus exporter server failed.")`.
3. `MetricManager` then calls `setGlobalMetricSet(globalRegistry)`, and the reporter calls
   `new MetricsCollector(globalMetricSet).register()`, which registers it in Prometheus'
   default `CollectorRegistry`, the one `HTTPServer(int)` serves.
4. On each scrape, `MetricsCollector.collect()`:
   - drops a metric whose name does not match `[a-zA-Z_:][a-zA-Z0-9_:]*` or whose tag does not
     match `[a-zA-Z_][a-zA-Z0-9_]*`, with a warning;
   - prefixes names with `amoro_`;
   - maps `MetricType.Counter` → `COUNTER` (value `Counter.getCount()`) and `MetricType.Gauge` →
     `GAUGE` (value `Gauge.getValue().doubleValue()`);
   - uses the metric's tags as label names and `MetricKey.valueOfTags()` as label values.

Metrics come from the server, for example `AmsServiceMetrics` (`ams_jvm_*`),
`OptimizerGroupMetrics` (`optimizer_group_*`), `TableOptimizingMetrics` (`table_optimizing_*`),
`TableSummaryMetrics` (`table_summary_*`) and `TableOrphanFilesCleaningMetrics`. They are exposed as
for example `amoro_optimizer_group_pending_tasks`.

## Configuration

`conf/plugins/metric-reporters.yaml` (`dist/src/main/fusion-bin/conf/plugins/`, and
`local-test/plugins/` for the local stack). Both ship with an empty list, so the exporter is **off**
by default. To turn it on:

```yaml
metric-reporters:
  - name: prometheus-exporter   # must equal PrometheusMetricsReporter.name()
    enabled: true
    properties:
      port: 7001
```

The port is not in `config.yaml` and is not exposed by `local-test/docker-compose.yml`. Exposing it
in a deployment means changing the compose file or OLake-Helm.

## State, threads and concurrency

`HTTPServer` runs its own threads. `collect()` runs on them and reads the registry's live metric
map, and gauges compute their values while the scrape runs.

## Contracts

- Plugin name `prometheus-exporter`, property `port`.
- Metric names `amoro_<name>`. Dashboards and alerts depend on these names; renaming the prefix or a
  metric breaks them.
- Plugin names must be unique in the YAML (`AbstractPluginManager.initialize` checks this).

## Extending or changing it

- To add a metric, define it in the server with `MetricDefine.defineCounter` or `defineGauge` and
  register it in the global registry. This collector picks it up without changes.
- New metric types need a case in both `covertType` and `covertValue`; otherwise a scrape throws
  `IllegalStateException`.
- Do not rename the package without also changing the service file.

## Invariants and pitfalls

- An exception in `collect()` fails that scrape. A gauge whose `getValue()` returns `null` throws a
  `NullPointerException` in `covertValue`.
- `close()` calls `server.close()` with no null check; it is only called for installed plugins,
  which have been opened.
- Every `setGlobalMetricSet` call registers another collector in the default registry. A second
  call (for example a reinitialized `MetricManager` in one JVM) would fail with Prometheus'
  duplicate-registration error.

## Tests

The module has no tests (`src/test` does not exist).

## Related packages

- [`io.olake.fusion.metrics`](../../../../../../../../../../fusion-core/src/main/java/io/olake/fusion/metrics/) in `fusion-core`: `MetricReporter`, `MetricSet`, `MetricRegistry`, `Counter`, `Gauge`.
- [`io.olake.fusion.server.manager`](../../../../../../../../../../fusion-ams/src/main/java/io/olake/fusion/server/manager/) in `fusion-ams`: `MetricManager`, `AbstractPluginManager`, `PluginConfiguration`.
