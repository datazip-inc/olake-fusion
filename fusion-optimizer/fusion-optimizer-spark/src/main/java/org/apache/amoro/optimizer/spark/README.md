# `org.apache.amoro.optimizer.spark`

A compatibility shim. It keeps the Spark optimizer's main class name from before the Amoro to
Fusion rename, so deployments that still start the old name keep working.

## Role in Fusion

The package has one class, `SparkOptimizer` (marked `@Deprecated`). Its `main(String[])` calls
`io.olake.fusion.optimizer.spark.SparkOptimizer.main(args)` and does nothing else. The constructor
is private.

It lives under `org.apache.amoro` because the main class is named outside this repo. Per the class
Javadoc and `TestSparkOptimizerShim`, OLake-Helm starts the optimizer with
`--class org.apache.amoro.optimizer.spark.SparkOptimizer`. If the class were removed or moved,
`spark-submit` in those deployments would fail with `ClassNotFoundException` and no optimizer would
register, so no table would be compacted.

The class ships in the same jar and image as the real optimizer
(`/opt/spark/usrlib/optimizer-job.jar` in `olakego/fusion-spark`). The shade plugin relocates only
`org.apache.parquet` and `org.apache.orc`, so the class name is unchanged in the shaded jar.

Inside this repo, `local-test/spark-optimizer.yaml` and
`io.olake.fusion.server.manager.SparkOptimizerContainer` (`SPARK_JOB_MAIN_CLASS`) already use the new
name, `io.olake.fusion.optimizer.spark.SparkOptimizer`.

## Key classes

| Class | Responsibility | Main collaborators |
| --- | --- | --- |
| `SparkOptimizer` | Legacy entry point; forwards `main` to the Fusion class. | `io.olake.fusion.optimizer.spark.SparkOptimizer` |

## How it works

`spark-submit --class org.apache.amoro.optimizer.spark.SparkOptimizer <jar> <args>` → `main(args)` →
`io.olake.fusion.optimizer.spark.SparkOptimizer.main(args)`. Arguments, behaviour and logging are
exactly those of the Fusion class.

## Contracts

- The fully qualified name `org.apache.amoro.optimizer.spark.SparkOptimizer` and a public static
  `main(String[])`.
- It must stay a separate class, not a subclass or alias, which the test checks.

## Extending or changing it

Do not add logic here. Remove the package only after every deployment (OLake-Helm's chart, and any
other place that sets `--class`) uses `io.olake.fusion.optimizer.spark.SparkOptimizer`, and after
the last image that those deployments may still pull is replaced. Remove
`TestSparkOptimizerShim` at the same time.

## Invariants and pitfalls

- The log4j2 routing in `docker/optimizer-spark/log4j2.xml` is configured for `io.olake.fusion*`
  loggers. This class does not log, so that does not matter here.
- Renaming the Fusion class breaks this shim at compile time, which is intended.

## Tests

`fusion-optimizer/fusion-optimizer-spark/src/test/java/io/olake/fusion/optimizer/spark/TestSparkOptimizerShim.java`:

```shell
./mvnw -o test -pl fusion-optimizer/fusion-optimizer-spark -am -Drat.skip=true \
  -Dtest=TestSparkOptimizerShim -Dsurefire.failIfNoSpecifiedTests=false
```

## Related packages

- [`io.olake.fusion.optimizer.spark`](../../../../../io/olake/fusion/optimizer/spark/): the real entry point.
