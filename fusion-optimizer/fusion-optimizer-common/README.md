# Optimizer common

The optimizer loop shared by the Spark and standalone optimizers: registration, heartbeat, and the poll, ack, execute and complete cycle.

## Packages

Each package has a README with its role, key classes, flows, threads, contracts, extension points, pitfalls and tests.

| Package | What it does |
| --- | --- |
| [`io.olake.fusion.optimizer.common`](src/main/java/io/olake/fusion/optimizer/common/README.md) | The optimizer runtime that every Fusion optimizer shares. |
