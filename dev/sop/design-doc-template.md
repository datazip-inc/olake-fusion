# <Feature title>

| | |
| --- | --- |
| Issue | #<number> |
| Author | <developer> |
| Reviewers | <names> |
| Doc type | Full / Lite |
| Status | Draft / In review / Approved / Implemented / Superseded |
| Last updated | <YYYY-MM-DD> |

**SOP progress** (ticked by the developer and the `/fusion-*` skills)

- [ ] 1 Design approved (`/fusion-design-check`: READY)
- [ ] 2 Test plan approved, tests red (`/fusion-write-tests`)
- [ ] 3 Implemented, tests green (`/fusion-implement`)
- [ ] 4 AI review: Open P0/P1 = 0 (`/fusion-verify`)
- [ ] 5 Developer review and manual test done
- [ ] 6 PR raised: <link>
- [ ] 7 Second review approved (`/fusion-pr-review`)
- [ ] 8 Merged

<!--

How to use this template:

- **Lite or Full.** Use **Lite** (sections marked [Lite]) for a change that is unambiguous, touches
  one area and changes no contract (no REST, property, config, schema or thrift change). Use **Full**
  otherwise. If you are unsure, use Full. A doc with no trade-offs to discuss is a sign the change
  may need only an issue, not a doc.
- **Every section of your doc type is filled in.** Write `N/A - <reason>` where it does not apply.
- **IDs.** Give stable IDs to user stories (US1), requirements (R1), success criteria (SC1), edge
  cases (E1) and risks (K1). The test plan, code review and PR refer to them. Never renumber; mark
  removed items ~~struck through~~.
- **Unknowns.** Mark anything undecided inline with `[NEEDS CLARIFICATION: <question>]`. The doc is
  not approved while one remains.
- **Terms.** Use OLake-UI terms (Lite, Medium, Full, Run) for what users see, and code terms (Minor,
  Major, Full, optimizing process) for the implementation.

Keep or delete this comment; dev/sop/check-sop.sh ignores HTML comments.
-->


## 1. Summary [Lite]

One paragraph: what changes and why, readable by someone who reads nothing else.

## 2. Context and problem [Lite]

The current behaviour and why it is a problem: who is affected, how it shows up (OLake-UI, API,
table state, logs, metrics), how often, and the evidence (issue, logs, measurements). Background a
reviewer needs; not the solution.

## 3. Goals and non-goals [Lite]

**Goals:** outcomes that will be true when this is done.

**Non-goals:** things a reasonable reader might expect this change to do, which it deliberately
does not. Say why.

## 4. User stories and acceptance scenarios

| ID | Priority | As a ... I want ... so that ... |
| --- | --- | --- |
| US1 | P1 | |

Acceptance scenarios for each story, in Given / When / Then form:

- **US1-A1**: Given <state>, when <action>, then <observable outcome>.

P1 stories alone must deliver a usable change; P2 and P3 can follow later.

## 5. Requirements and success criteria [Lite]

**Functional requirements:** each one testable, in MUST language.

| ID | Requirement | Story |
| --- | --- | --- |
| R1 | Fusion MUST ... | US1 |

**Success criteria:** measurable, and observable without reading the code.

| ID | Criterion | How it is measured |
| --- | --- | --- |
| SC1 | | |

## 6. Proposed design

Start with an overview a reviewer can hold in their head, then the details.

- **Components.** Modules, classes and methods to change or add, and why there. Mark what runs in
  the **server** (`amoro-ams`; planners and evaluators in `amoro-format-iceberg`) and what runs in
  the **optimizer** (rewrite executors; `amoro-optimizer-*`).
- **Flow.** The sequence from trigger to result (cron fires, then evaluate, plan, execute, commit,
  and so on). A list or a diagram.
- **State.** Table runtime and optimizing process states that are added, entered or left, and the
  path back to `IDLE` on every failure.
- **Data and persistence.** Postgres tables or columns (as a `V<n>__*.sql` migration), Iceberg
  metadata written, task properties.
- **Interfaces.** New or changed REST endpoints (with request and response examples), properties,
  config keys, thrift fields.
- **Concurrency.** Which threads and processes touch the new state (schedulers, commit executor,
  REST handlers, optimizer threads, OLake writing the same table, a second AMS replica) and how they
  stay consistent.

## 7. Alternatives considered and drawbacks

| Option | Pros | Cons | Why not chosen |
| --- | --- | --- | --- |
| Do nothing | | | |
| <alternative> | | | |

**Drawbacks of the chosen design:** why we might not want to do this.

## 8. Contracts and compatibility

Write `No change` in a row where nothing changes.

| Contract | Change | Who is affected | Backward compatible? |
| --- | --- | --- | --- |
| REST API (`/api/ams/v1/...`) used by OLake-UI | | | |
| Terminal API (table settings from OLake-UI) | | | |
| Table properties (`self-optimizing.*`, ...) | | | |
| Catalog properties | | | |
| Server config keys (`config.yaml`) / env vars | | | |
| Postgres schema | | | |
| Thrift API between server and optimizer | | | |
| Iceberg metadata written to tables | | | |
| Metrics and log lines others depend on | | | |
| Required OLake-UI / OLake-Helm changes | | | |

**Upgrade:** what happens to an existing deployment, its database, existing tables and in-flight
optimizing processes when this version rolls out. What an operator must change, if anything.

**Downgrade / rollback:** what happens if the previous version is redeployed after this one ran
(new columns, new properties, new task fields it does not understand).

**Version skew:** behaviour while the server and the Spark optimizer image run different versions,
in both directions, and while OLake-UI is older or newer than Fusion.

## 9. Edge cases and failure modes [Lite]

| ID | Scenario | Expected behaviour |
| --- | --- | --- |
| E1 | | |

Consider at least the following and list each that applies:

- **Table shape:** empty table, no snapshot, unpartitioned, partition evolution, schema evolution,
  very wide or very large table, many small files, equality deletes, position deletes.
- **Concurrency:** OLake commits while the run is in progress (commit conflict); two crons fire
  together; a run is still active when its next cron fires.
- **Failure:** optimizer crash or pod eviction mid-task, task retried or completed late, server
  restart in each state, Postgres unavailable, object store or catalog errors and throttling.
- **Configuration:** property missing, empty, invalid or changed mid-run; cron "Never"; optimization
  disabled or table dropped while running.

## 10. Cross-cutting concerns

- **Security:** credentials, auth on new endpoints, input validation, what is logged.
- **Performance and scale:** cost in object-store calls, Postgres queries, memory, per table and
  per file; expected numbers (tables, files, partitions) and how the design behaves at 10x.
- **Observability:** logs (with table identifier), metrics, API fields that show it works or fails.
- **Operability:** how to enable and disable it, defaults, how an operator troubleshoots it, and what
  happens when a dependency (Postgres, object store, catalog, Kubernetes) is down.

## 11. Risks and mitigations

| ID | Risk | Likelihood / impact | Mitigation |
| --- | --- | --- | --- |
| K1 | | | |

## 12. Test strategy [Lite]

Which levels prove which requirements: unit, AMS service tests, the integration suite
(`integration-tests/`), and the manual test recorded for the PR demo. Name what cannot be tested
automatically and how it will be checked instead. Details go in `test-plan.md` (stage 2).

## 13. Rollout and rollback [Lite]

Default on or off, any flag, the order to deploy (server, optimizer image, OLake-UI), how to tell it
works in production, and how to turn it off without data loss.

## 14. Assumptions and open questions [Lite]

**Assumptions:** defaults chosen where the requirement was silent.

**Open questions:** every `[NEEDS CLARIFICATION]` in the doc, with an owner. Approval needs this list
empty or every item marked non-blocking.

## 15. Prior art and references

How upstream Amoro, Iceberg (for example its `RewriteDataFiles` and `RewritePositionDeleteFiles`
actions) or similar systems solve this, and links to issues, PRs and earlier design docs.

## 16. Changelog

| Date | Change | By |
| --- | --- | --- |
| | Initial draft | |
