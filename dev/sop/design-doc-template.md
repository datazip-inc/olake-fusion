# <Feature title>

| Issue | Owner | Reviewers | Doc type | Status | Updated |
| --- | --- | --- | --- | --- | --- |
| #<number> | <developer> | <names> | Full / Lite | Draft / Approved / Done | <YYYY-MM-DD> |

SOP progress (tick as each stage passes): [ ] 1 Design approved · [ ] 2 Tests red · [ ] 3 Implemented ·
[ ] 4 Verified · [ ] 5 PRs raised · [ ] 6 AI review clean · [ ] 7 Developer review · [ ] 8 Human review ·
[ ] 9 Merged

<!--
Rules (this comment can stay; dev/sop/check-sop.sh ignores comments):
- Lite = no contract change and one area; fill sections marked [Lite]. Otherwise Full.
- Be short: tables and one-line answers. No section is skipped; write "N/A - <reason>".
- IDs (R1, E1, ...) are stable; never renumber, strike removed rows through (~~R3~~).
- Undecided: write [NEEDS CLARIFICATION: <question>]. The doc is approved only when none remain
  and every edge case row has an answer.
- Section 3 is written for users: product docs are made from it. No class names there.
-->

## 1. Summary [Lite]

Two or three sentences: what changes, for whom, why.

## 2. Problem and goals [Lite]

- **Problem:** what is wrong today and the evidence (issue, logs, numbers).
- **Goals:** what is true when done.
- **Non-goals:** what this deliberately does not do.

## 3. User-facing behaviour [Lite]

Written for users and product docs, in OLake-UI terms (Lite, Medium, Full, Run).

- **What users see or do:** one short paragraph per scenario, before and after.
- **Settings:**

| Setting (UI name) | Property / config key | Default | Allowed values | Effect |
| --- | --- | --- | --- | --- |

- **Limits and caveats users must know:**
- **Errors and messages users can see:** message, cause, what the user should do.
- **How users learn it worked or failed:** the screen and field for each outcome, and how soon.
- **Docs and UI changes needed:** olake-docs pages, release notes, UI copy, OLake-UI changes.

## 4. Requirements [Lite]

| ID | Requirement (Fusion MUST ...) | Acceptance (Given / When / Then) |
| --- | --- | --- |
| R1 | | |

## 5. Design

- **Components:** classes and modules changed, each with its row in `dev/sop/architecture.md`
  section 4; server-side or optimizer-side.
- **Flow:** trigger to result, as numbered steps.
- **State and persistence:** states entered or left (with the way back to `IDLE` on failure),
  Postgres changes (migration), Iceberg metadata written.
- **Concurrency:** which threads and processes touch the new state, and how they stay consistent.
- **Core changes:** each change to Fusion core logic (`dev/sop/architecture.md` section 6) with why
  an extension point or a small hook cannot do it, and how today's behaviour stays unchanged.
  Write "None" when the feature is built entirely beside the core.
- **Alternatives rejected:** at least one real alternative (another design, reusing existing code, an
  Iceberg API), and why the chosen one is better, one line each.

## 6. Compatibility

| Contract | Change (or "No change") | Backward compatible? |
| --- | --- | --- |
| REST API / terminal API used by OLake-UI | | |
| Table and catalog properties | | |
| Server config keys, env vars | | |
| Postgres schema | | |
| Thrift / task properties (server - optimizer) | | |
| OLake-UI / OLake-Helm changes needed | | |

- **Upgrade:** effect on existing deployments, tables and running processes.
- **Rollback:** effect of redeploying the previous version after this one ran.
- **Version skew:** server and optimizer image on different versions, both directions.

## 7. Edge cases [Lite]

Every row needs the expected behaviour, or `N/A - <reason>`. Add feature-specific rows from E17.

| ID | Case | Expected behaviour |
| --- | --- | --- |
| E1 | Empty table or table with no snapshot | |
| E2 | Partitioned, unpartitioned, and partition spec evolved | |
| E3 | Schema evolved since the files were written | |
| E4 | Equality deletes and position deletes present | |
| E5 | Very large table (many files or partitions) and very small table | |
| E6 | OLake commits to the table while this runs (commit conflict) | |
| E7 | Two crons fire together, or a run is still active when the next fires | |
| E8 | Optimizer crashes, is evicted, or a task is retried or completes late | |
| E9 | Server restarts at each state this feature uses | |
| E10 | Postgres unavailable | |
| E11 | Object store or catalog errors, throttling, missing files | |
| E12 | Setting missing, empty, invalid, or changed during a run | |
| E13 | Optimization disabled, cron set to Never, or table dropped during a run | |
| E14 | Upgrade or rollback with runs in flight | |
| E15 | Concurrent API calls from OLake-UI for the same table | |
| E16 | Permissions or credentials missing or expired | |

## 8. Non-functional

- **Security:** credentials, auth on endpoints, input validation, what is logged.
- **Performance:** cost per table and per file (object-store calls, queries, memory); behaviour at
  10x the expected size.
- **Observability:** logs and metrics that show it works or fails.
- **Operability:** how to turn it off; what an operator does when it misbehaves.
- **Risks:** risk and mitigation, one line each.

## 9. Testing and rollout [Lite]

- **Tests:** which levels prove which R and E rows (unit, AMS service, integration); what is only
  tested manually.
- **Demo:** the manual scenario recorded for the PR.
- **Rollout and rollback:** default on or off, deploy order (server, optimizer image, OLake-UI), how
  to disable without data loss.

## 10. Open questions [Lite]

Every `[NEEDS CLARIFICATION]` with an owner. Must be empty to approve.
