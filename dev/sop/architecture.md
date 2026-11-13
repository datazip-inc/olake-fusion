# Fusion architecture: what new code must follow

The map of how Fusion is built, and where each kind of change belongs. `/fusion-implement` designs
every change from it, `/fusion-pr-review` reviews against it, and the human reviewer uses it in stage 8.
`fusion-invariants.md` says what must stay true; this file says how code is shaped.

## Contents

1. Runtime picture
2. Lifecycle of one compaction
3. Module layering
4. Where each kind of change goes
5. Coding rules
6. Design principles
7. Code quality: principles and smells
8. Testing: which level proves what

## 1. Runtime picture

| Process | Runs | Code |
| --- | --- | --- |
| Fusion server (AMS) | Container (Docker) or pod (Helm) | `amoro-ams` + `amoro-format-iceberg` + `amoro-common` |
| Spark optimizer | Spark job on Kubernetes, image `olakego/fusion-spark` | `amoro-optimizer-spark` + `-common` + `amoro-format-iceberg` + `amoro-common` |
| Postgres | Fusion's own state: catalogs, table runtimes, processes, tasks | `postgres/migration/*.sql`, MyBatis mappers |
| Catalog + object store | Iceberg metadata and data files (OLake writes the same tables) | Iceberg 1.7.2 APIs |
| OLake-UI | Product UI and its Go backend; calls `/api/ams/v1/...` | Other repo |

The server and the optimizer are separate processes, possibly on different versions during a
rollout, and talk only through the thrift `OptimizingService`.

## 2. Lifecycle of one compaction

1. **Cron fires.** `TableRuntimeRefreshExecutor` evaluates the table's
   `self-optimizing.{minor,major,full}.trigger.cron` (`CronUtils`), checks the work is still necessary
   (the snapshot changed or the last run left work), and sets the table runtime to `PENDING`.
2. **Planning.** `OptimizingQueue` (scheduling by `SchedulingPolicy`, quota) picks the table, marks
   it `PLANNING` and calls `planInternal`, which creates the planner through
   `IcebergTableUtil.createOptimizingPlanner`: `IcebergOptimizingPlanner` with
   `IcebergPartitionPlan` / `CommonPartitionEvaluator`. The result is an `OptimizingProcess` with
   `TaskRuntime`s, persisted in Postgres; status becomes `MINOR_`, `MAJOR_` or `FULL_OPTIMIZING`.
3. **Execution.** Optimizer threads (`OptimizerExecutor`) call `pollTask`, `ackTask`,
   `completeTask` on `DefaultOptimizingService` (thrift). Each task runs `IcebergRewriteExecutor`
   (`AbstractRewriteFilesExecutor`) in the optimizer and returns the files it wrote.
4. **Commit.** When all tasks succeed, the process moves the table to `COMMITTING`;
   `OptimizingCommitExecutor` picks up `COMMITTING` tables and `UnKeyedTableCommit` commits the rewrite to Iceberg, validating against concurrent OLake
   commits.
5. **Back to idle.** The process is closed (success or failure) and the table returns to `IDLE`.
   Failed tasks are retried through `OptimizingQueue.retryTask`; tasks of an optimizer that stops
   touching the server are suspended and requeued by `OptimizerKeeper` in
   `DefaultOptimizingService`. `OptimizingExpiringExecutor` deletes old process records.

Status changes and config changes reach other components through `RuntimeHandlerChain`
(`handleStatusChanged`, `handleConfigChanged`, `handleTableAdded`, `handleTableRemoved`).

## 3. Module layering

```
amoro-common            shared types, config (ConfigOptions), thrift API, exceptions, metrics API
   ^
amoro-format-iceberg    Iceberg logic: evaluators and planners (used by the server),
   ^                    rewrite executors (used by the optimizer), TableProperties
   |
   +-- amoro-ams                       server only: scheduling, runtime, persistence, REST
   +-- amoro-optimizer-common/-spark   optimizer only
```

- Dependencies point down only. The optimizer never depends on `amoro-ams`; shared code that both
  need goes in `amoro-format-iceberg` or `amoro-common`.
- Code that runs in the optimizer must work with an older and a newer server (thrift fields and
  task properties optional, never repurposed).
- Do not add code to out-of-scope modules (mixed formats, Hive, Paimon, Hudi, Flink, `amoro-web`).
  Where shared code switches on `TableFormat`, change the Iceberg branch only.

## 4. Where each kind of change goes

Follow the named existing example; do not build a parallel mechanism.

| Change | Where | Follow | Also update |
| --- | --- | --- | --- |
| REST endpoint | Controller in `server/dashboard/controller/`; route in `DashboardServer.apiGroup()` | `TableController#getTableDetail`: read path params from `Context`, return `ctx.json(OkResponse.of(...))`; throw typed exceptions, which `DashboardServer.handleException` maps to `ErrorResponse` | `integration-tests` `Contract.ApiPaths`; grep OLake-UI callers |
| Server config key | `ConfigOptions.key(...)` in `AmoroManagementConf`, validation in `AmoroManagementConfValidator` | Existing options there | `dist/src/main/amoro-bin/conf/config.yaml`, `local-test/config.yaml`; env override is `AMS_` + key with `-` as `__` (`ConfigHelpers.convertConfigurationKeys`); OLake-Helm and OLake-UI compose |
| Table property | Constant and default in `TableProperties` (`amoro-format-iceberg`); parsed in `TableConfigurations.parseTableConfig` into `TableConfiguration` / `OptimizingConfig` | The `self-optimizing.*.trigger.cron` properties | React to changes in `handleConfigChanged`; OLake-UI `mapper.go` if users set it |
| Periodic per-table job | Subclass of `PeriodicTableScheduler`, registered in `InlineTableExecutors.setup` | `TableHealthScoreExecutor` | Enable flag, interval and pool size as config keys |
| Reacting to table events | A `RuntimeHandlerChain` handler | `DefaultOptimizingService`'s handler | - |
| Persisted state | MyBatis annotation mapper in `server/persistence/mapper/`; access through `PersistentBase` (`doAs`, `getAs`, `doAsTransaction`) | `TableRuntimeMapper` | Schema in a new `postgres/migration/V<n>__*.sql` registered in `SchemaMigrator` |
| When or what to compact | `IcebergOptimizerEvaluator`, `CommonPartitionEvaluator`, `IcebergOptimizingPlanner`, `IcebergPartitionPlan` | Existing evaluator rules | Planner tests in `amoro-format-iceberg` |
| How files are rewritten | `IcebergRewriteExecutor` / `AbstractRewriteFilesExecutor` (runs in the optimizer) | Existing executor | Rebuild the optimizer jar to test (`CLAUDE.md` section 5) |
| Server-optimizer data | `amoro_optimizing_service.thrift`, `TaskProperties` | Existing optional fields | Both directions of version skew |
| Metrics | `MetricDefine` registered through `MetricRegistry` | `TableOptimizingMetrics`, `AmsServiceMetrics` | Grafana dashboards in `grafana/` if they use it |
| Errors | Exception types in `amoro-common` `org.apache.amoro.exception` (`ObjectNotExistsException`, `ForbiddenException`, `AmoroRuntimeException`, ...) | - | - |

## 5. Coding rules

- Guava from the shaded package (`org.apache.amoro.shade.guava32...`); argument checks with
  `Preconditions`.
- Loggers: `private static final Logger LOG = LoggerFactory.getLogger(X.class)` (slf4j). Every log
  line about a table includes its identifier. Log an exception once, where it is handled.
- No new threads outside managed executors: name them and shut them down in the owner's
  `dispose`.
- Behaviour that operators may need to tune is a config key or table property with a safe default,
  not a constant.
- No new third-party dependency without the developer's agreement: it has to be licensed,
  possibly shaded, and shipped in both the server and the Spark images.
- Iceberg calls use only APIs that exist in 1.7.2.
- Match the surrounding code's naming, comment density and test framework.

## 6. Design principles

- **Touch the Fusion core as little as possible.** New features are added beside the core, not
  inside it. The core is the compaction path and its state:
  `TableRuntimeRefreshExecutor`, `SchedulingPolicy`, `OptimizingQueue`, `OptimizingProcess` /
  `TaskRuntime`, `DefaultOptimizingService`, `DefaultTableRuntime` and `OptimizingStatus`,
  `OptimizingCommitExecutor` / `UnKeyedTableCommit`, the evaluators and planners, the rewrite
  executors, `SchemaMigrator` and the existing mappers. In order of preference:
  1. **Extension point, no core change:** a new `RuntimeHandlerChain` handler, a new
     `PeriodicTableScheduler`, a new controller, a new table property or config key read where it
     is needed, a new class implementing an existing interface.
  2. **A small hook in the core:** one call or one branch that delegates to new code, guarded by a
     setting whose default keeps today's behaviour.
  3. **Changing core logic:** only when 1 and 2 cannot work. The design doc lists every such
     change under "Core changes" (section 5), why the alternatives fail, and how existing behaviour is proven unchanged (existing
     tests pass untouched, plus a test for the default path).
- **No parallel mechanisms.** Extending beside the core does not mean a second scheduler, queue,
  config system or persistence path: plug into the existing ones (section 4).
- **Small, isolated Fusion changes.** Fusion merges upstream Amoro changes (`dev/merge_amoro_pr.py`).
  New code in new classes, core edits kept to a few lines, so upstream merges stay easy. Mark
  modified files with the Datazip header.
- **One optimizer group, one optimizer.** No multi-group code paths.
- **Compatibility by default.** Additive API and schema changes; old clients, old optimizers and
  old databases keep working.
- **Fail safe.** A failure stops this table's run and leaves Iceberg and Postgres consistent; it
  never blocks other tables.

## 7. Code quality: principles and smells

Every change is written, and reviewed, against these. They apply to new code; do not refactor
untouched code to meet them.

**Pick the best implementation, not the first one.** For each non-trivial piece, compare at least
two ways to build it and choose the one that is simplest, reuses existing code, and fits the
principles below. Before writing anything new, search for an existing utility or Iceberg API that
already does it (`IcebergTableUtil`, `CronUtils`, `PropertyUtil`, `JacksonUtil`, `ConfigHelpers`,
Iceberg's `TableScan`, `RewriteFiles`, `PropertyUtil`).

| Principle | In Fusion | Violation looks like |
| --- | --- | --- |
| **S**ingle responsibility | A controller parses the request and calls a manager; logic lives in the manager or service; SQL lives in a mapper | Business logic or SQL in a controller; one method that loads, decides, writes and logs |
| **O**pen/closed | New behaviour through extension points (section 6): a handler, a scheduler subclass, a new class | Growing `if`/`switch` chains on type or mode inside core classes |
| **L**iskov substitution | A `PeriodicTableScheduler` or `RuntimeHandlerChain` subclass honours the parent's contract | Overriding to throw `UnsupportedOperationException` or to silently do nothing |
| **I**nterface segregation | Small interfaces with what callers need | Adding a method to `TableService` / `TableManager` / `TableRuntime` that one caller uses |
| **D**ependency inversion | Depend on interfaces passed through the constructor (`TableController` gets `TableManager`, `CatalogManager`) | `new` of collaborators or static singletons inside logic, which also makes it untestable |

Also:

- **DRY:** one source of truth for a rule, constant or query. Copy-pasted blocks become one method.
- **KISS and YAGNI:** the simplest design that meets the requirements; no options, hooks or
  generality the design doc does not ask for.
- **Small units:** a method does one thing and fits on a screen; a class has one reason to change.
- **Clear names:** intent-revealing names; no abbreviations, no `flag`, `data`, `tmp`, `handle2`.
- **Immutability:** fields `final` where possible; return unmodifiable or defensive copies of
  collections that callers must not change.
- **Fail fast:** validate inputs at the boundary with `Preconditions` and clear messages.
- **Errors:** catch the narrowest exception you can handle; never swallow one; rethrow with context
  (table identifier, operation).
- **Resources:** close Iceberg `CloseableIterable`s, `FileIO` streams and readers with
  try-with-resources.
- **Testable by design:** logic that can be unit tested without the server; time and randomness
  injected, not read inline.

**Smells that block review (P1)**, each with the fix:

| Smell | Fix |
| --- | --- |
| Logic duplicated from existing code or reimplementing an Iceberg API | Reuse the existing method or API |
| God method or class (many responsibilities, long branching) | Split by responsibility |
| Boolean or mode parameters that switch behaviour | Separate methods or a strategy object |
| `catch (Exception e)` that logs and continues, or an empty catch | Narrow catch, handle or rethrow with context |
| Unclosed `CloseableIterable`, stream or reader | try-with-resources |
| Static mutable state or singletons used for logic | Constructor-injected dependency |
| Magic numbers or strings for tunables | Named constant, config key or table property |
| Mutable shared collection exposed by a getter | Unmodifiable view or copy |
| Speculative abstraction or option nobody asked for | Remove it (YAGNI) |

## 8. Testing: which level proves what

| Level | Where | Runs in CI | Database |
| --- | --- | --- | --- |
| Unit | `<module>/src/test` | Core CI (`mvn install`) | none |
| AMS service | `amoro-ams/src/test` (`AMSServiceTestBase`, `AMSTableTestBase`, `ExecutorTestBase`) | Core CI | Derby (migrations do not run) |
| Integration | `integration-tests/` (`ItBase`, `Contract`, `FusionIT`, `CompactionLifecycleIT`) | `integration-tests.yml`, real stack: Postgres, MinIO, Spark optimizer on Kind, OLake fixtures | Postgres |

The minimum each kind of change needs. A change that fits several rows needs all of them.

| Change | Unit | AMS service | Integration |
| --- | --- | --- | --- |
| Pure logic: evaluator or planner rule, cron, property or config parsing, validation | Required | - | - |
| Table runtime state, scheduling, queue, handlers, commit executor | Required where logic can be isolated | Required | When users see the outcome (run type, skip reason, health score): required, `FusionIT` style |
| REST endpoint or response field used by OLake-UI | - | Required (controller) | Required: update `Contract` and assert the field |
| Persistence with portable SQL (mapper) | - | Required | - |
| Postgres-specific SQL or a schema migration | - | Not enough (Derby) | Required, or a runtime check on Postgres recorded in `verify.md` |
| Rewrite executor or commit semantics (rows, deletes, conflicts with OLake writes) | Required (`amoro-format-iceberg`) | - | Required, `CompactionLifecycleIT` style |
| Server config key or env var | Required (parsing, defaults) | - | When it changes deployment: required, via `integration-tests/docker/fusion-config.overlay.yaml` |
| Server-optimizer compatibility (thrift, task properties) | Required (old and new field sets) | - | Version skew is checked manually in stage 7 |

Integration tests that need exclusive table state are tagged `serial`. Run one locally with
`make integration-test TEST_ONLY=<Class#method>`.

