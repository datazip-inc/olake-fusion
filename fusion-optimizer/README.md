# Fusion optimizer

The process that executes compaction tasks. It registers with the server, polls tasks over Thrift, runs the rewrite and reports the result. In production this is the Spark optimizer (`fusion-optimizer-spark`, image `olakego/fusion-spark`) on Kubernetes; the standalone optimizer is only for local debugging. Fusion has one optimizer group and one optimizer.

## Modules

| Module | What it does |
| --- | --- |
| [`fusion-optimizer-common`](fusion-optimizer-common/README.md) | The optimizer loop shared by the Spark and standalone optimizers: registration, heartbeat, and the poll, ack, execute and complete cycle. |
| [`fusion-optimizer-spark`](fusion-optimizer-spark/README.md) | The production optimizer: runs the common optimizer loop on a Spark driver and executes each task on an executor. |
| [`fusion-optimizer-standalone`](fusion-optimizer-standalone/README.md) | A single-JVM optimizer started by the server through `bin/optimizer.sh` for local debugging. |
