# Standalone optimizer

A single-JVM optimizer for local debugging: started by the server through `bin/optimizer.sh`, or directly with `make debug-optimizer`. From source it runs the compiled classes.

## Packages

Each package has a README with its role, key classes, flows, threads, contracts, extension points, pitfalls and tests.

| Package | What it does |
| --- | --- |
| [`io.olake.fusion.optimizer.standalone`](src/main/java/io/olake/fusion/optimizer/standalone/README.md) | The standalone optimizer: one plain JVM that runs the common optimizer loop with no Spark. |
