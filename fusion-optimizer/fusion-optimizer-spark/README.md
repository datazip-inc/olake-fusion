# Spark optimizer

The production optimizer: runs the common optimizer loop on a Spark driver and executes each task on an executor. Shipped in the `olakego/fusion-spark` image.

## Packages

Each package has a README with its role, key classes, flows, threads, contracts, extension points, pitfalls and tests.

| Package | What it does |
| --- | --- |
| [`io.olake.fusion.optimizer.spark`](src/main/java/io/olake/fusion/optimizer/spark/README.md) | The production optimizer. |
| [`org.apache.amoro.optimizer.spark`](src/main/java/org/apache/amoro/optimizer/spark/README.md) | A compatibility shim. |
