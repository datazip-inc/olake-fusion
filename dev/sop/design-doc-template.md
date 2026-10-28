# <Feature title>

- Issue: #<number>
- Author: <developer>
- Status: Draft | Ready for tests | Implemented
- Last updated: <YYYY-MM-DD>

Every section must be filled in. Write `N/A` with a reason when a section does not apply; do not
delete sections. Give each requirement and edge case an ID (R1, E1, ...): the test plan and reviews
refer to them.

## 1. Problem

What is wrong or missing today, who is affected, and how it shows up (OLake-UI, API, table state,
logs). Link evidence: issue, logs, metrics.

## 2. Goals and non-goals

Goals: what will be true when this is done.

Non-goals: what this change deliberately does not do. Mention anything a reviewer might expect but
will not find.

## 3. User-visible behaviour

What a user sees in OLake-UI or through the API before and after. Use OLake-UI terms here (Lite,
Medium, Full, Run) and name the Fusion terms (Minor, Major, Full, optimizing process) next to them.

## 4. Requirements

| ID | Requirement | Notes |
| --- | --- | --- |
| R1 | | |

Each requirement must be testable: state the input, the expected outcome and where it is observed.

## 5. Design

How it works. Name the modules, classes and methods to change or add, and why there. Mark which
code runs in the server (`amoro-ams`, planners and evaluators in `amoro-format-iceberg`) and which
runs in the optimizer (rewrite executors, `amoro-optimizer-*`).

- Data flow / sequence (a short list or diagram is enough).
- State changes: which table runtime or optimizing process states are added, entered or left.
- Concurrency: which threads or processes touch the new state (schedulers, commit executor,
  optimizer threads, REST handlers, a second AMS replica) and how they are kept consistent.
- Alternatives considered and why they were rejected.

## 6. Contracts and compatibility

Fill in each row; write `No change` where nothing changes.

| Contract | Change | Who is affected | Backward compatible? |
| --- | --- | --- | --- |
| REST API (`/api/ams/v1/...`) used by OLake-UI | | | |
| Table properties (`self-optimizing.*`, ...) | | | |
| Catalog properties | | | |
| Server config keys (`config.yaml`) / env vars | | | |
| Postgres schema (`postgres/migration/V<n>__*.sql`) | | | |
| Thrift API between server and optimizer | | | |
| Iceberg metadata written to tables | | | |
| Metrics / logs others depend on | | | |
| OLake-UI / OLake-Helm changes needed | | | |

Upgrade: what happens to an existing deployment, existing tables and running optimizing processes
when this version is rolled out, and when it is rolled back.

## 7. Edge cases and failure modes

| ID | Scenario | Expected behaviour |
| --- | --- | --- |
| E1 | | |

Think through at least: empty table, table with no snapshot, partitioned and unpartitioned tables,
equality and position deletes, schema or partition evolution, very large tables, concurrent writes
by OLake during compaction (commit conflict), optimizer crash or restart mid-task, server restart
mid-process, Postgres unavailable, object store errors, invalid or missing property values, cron
edge cases (Never, overlapping runs), and the table being dropped or optimization disabled while a
run is in progress.

## 8. Testing approach

Which levels prove which requirements: unit tests, AMS service tests, the integration suite
(`integration-tests/`), and the manual test you will record for the PR demo.

## 9. Rollout and observability

Feature flags or defaults, how an operator sees that it works (logs, metrics, API fields), and how to
turn it off if it misbehaves.

## 10. Open questions

Anything still undecided. The doc is not ready for stage 2 while a question here blocks a
requirement.
