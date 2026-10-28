# Fusion Invariants and Caveats

The checklist that implementation and review (human and AI) check every change against. A change
that breaks an invariant here is P0 if it is clearly broken and P1 if it is at risk. `CLAUDE.md`
holds the scope, deployment and terminology; this file holds what must stay true in the code.

When you learn a new invariant the hard way (a bug, an incident, a review finding), add it here in
the same PR.

## 1. Iceberg table data

- **No data loss, no duplicates.** A rewrite must produce exactly the live rows of its inputs: same
  rows, deletes applied, nothing dropped by a partition, schema or spec mismatch.
- **Commits are conflict-safe.** OLake writes to the same tables while Fusion compacts them. The
  commit (`UnKeyedTableCommit`, run by `OptimizingCommitExecutor`) must validate against concurrent
  snapshots and fail cleanly on conflict instead of overwriting newer data. A failed commit leaves
  the table unchanged; files written by the optimizer for it are orphans, not table content.
- **Equality deletes keep their sequence-number semantics.** Never rewrite a data file in a way that
  makes an older equality delete stop applying to it, or a newer one start applying.
- **Iceberg version.** Fusion pins Iceberg `1.7.2`. Read Iceberg code at that tag and use only APIs
  that exist in it (`CLAUDE.md` section 3).
- **Tables Fusion does not own.** Fusion must not change table properties it does not own, and never
  touches `olake_2pc` (OLake's two-phase-commit state).

## 2. Scheduling and table runtime

- **Crons drive compaction.** A table is compacted only when one of its
  `self-optimizing.{minor,major,full}.trigger.cron` fires (`TableRuntimeRefreshExecutor`,
  `SchedulingPolicy`, `CronUtils`). An empty cron means Never. A change must not make a table run
  without a cron firing, or stop it running when one fires and work is needed.
- **One process per table at a time.** A table has at most one active optimizing process. Status
  transitions follow `OptimizingStatus` (`PENDING`, `PLANNING`, `*_OPTIMIZING`, `COMMITTING`,
  `IDLE`); a new transition must be reachable and must have a way back to `IDLE` on every failure
  path, including server restart. A table stuck in `PLANNING`, `COMMITTING` or `PENDING` forever is
  a P0.
- **Disabled means disabled.** When `self-optimizing.enabled` is false, or the table is dropped,
  scheduling stops and any in-flight process ends cleanly.
- **One optimizer group, one optimizer** (`CLAUDE.md` section 1). Do not add multi-group logic.
- **Quota.** `self-optimizing.quota` limits each table's share of the optimizer; new scheduling must
  respect it.

## 3. Server and optimizer

- **The optimizer runs other jars.** Code in rewrite executors (`amoro-format-iceberg`) and
  `amoro-optimizer-*` runs in the Spark optimizer on Kubernetes, possibly an older or newer image
  than the server during a rollout. Anything sent between them over thrift
  (`amoro-common/src/main/thrift/`) or in task properties must stay compatible both ways: add
  optional fields, never repurpose or remove them.
- **Tasks can be retried and can time out.** An optimizer task may run twice, run on a different
  executor after a crash, or complete after the server gave up on it. Task completion must be
  idempotent and late results for a cancelled process must be ignored, not committed.
- **Server restarts and HA.** Everything needed to resume or clean up a process is persisted in
  Postgres, not only in memory. Assume a restart can happen between any two statements.

## 4. Persistence (Postgres)

- **Schema changes are migrations.** Add `amoro-ams/src/main/resources/postgres/migration/V<n>__<name>.sql`
  and register it in `SchemaMigrator.MIGRATIONS`. Scripts are idempotent (`IF NOT EXISTS`), never
  edited after they ship, have no down migration, and no `COMMIT`/`ROLLBACK`. See the
  `SchemaMigrator` Javadoc.
- **Postgres only.** Ignore other databases (`CLAUDE.md` section 1).
- MyBatis mappers live in `server/persistence/mapper`. Multi-row state changes that must be atomic go
  in one transaction (`PersistentBase` / `NestedSqlSession`).

## 5. Contracts with OLake-UI and OLake-Helm

- **REST API.** OLake-UI calls Fusion's `/api/ams/v1/...` directly and through its proxy
  (`CLAUDE.md` section 4). Changing a path, request or response field, status code or the response
  envelope breaks OLake-UI unless it changes too. Additive, optional fields are safe.
- **Terminal API.** OLake-UI writes table settings with `ALTER TABLE ... SET TBLPROPERTIES` through
  `/terminal/...`, then polls the logs. Changing its behaviour or timing affects table settings.
- **Properties and config.** A new or renamed table property, catalog property, config key
  (`config.yaml`, `AmoroManagementConf`) or env var must be checked against OLake-UI's
  `mapper.go` / `docker-compose-v1.yml` and OLake-Helm's `templates/fusion/`. Defaults must keep
  existing deployments working without edits.
- **Units.** OLake-UI sends target file size in MB and writes `self-optimizing.target-size` in bytes.

## 6. Security

- No secrets (catalog credentials, S3 keys, tokens, passwords) in logs, exceptions returned by the
  API, metrics labels or committed files.
- REST endpoints keep their authentication (login session or API token signature). New endpoints
  are not public unless the design doc says so.
- Input from the API or table properties is validated; nothing user-supplied is concatenated into
  SQL outside the terminal feature.

## 7. Reliability and performance

- Scheduler and executor threads must not block on slow I/O while holding a lock that other tables
  need. One bad table (missing files, permission error, corrupt metadata) must not stop others.
- Planning and evaluation scale with the number of files: avoid repeated full scans of large
  tables, loading all manifests into memory, or per-file calls to the object store or Postgres.
- Exceptions are logged once, with the table identifier, and do not leave state half-updated.

## 8. Code and repository rules

- Follow the surrounding code: package placement, naming, logging style, test framework (JUnit 4 or
  5, whichever the module and its neighbours use).
- Out-of-scope modules (mixed formats, Hive, Paimon, Hudi, Flink optimizer, `amoro-web`) are not
  changed unless the design doc says so. If shared code affects them, say so in one line.
- Every modified file with the Apache header has `Modified by Datazip Inc. in <year>` within its
  first 40 lines. Format with `./mvnw -o -q spotless:apply -pl <module>`.
