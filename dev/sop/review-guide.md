# Review guide

Shared by `/fusion-verify` (stage 4), `/fusion-pr-review` (stage 7) and `/fusion-address-review`.
Human reviewers can use it as is.

## Contents

- Severity
- Independent reviewers
- Lenses
- Confidence and false-positive filter
- Finding format

## Severity

Only P0 and P1 are reported. Anything below is noise in these stages.

- **P0**: data loss, duplication or corruption; a wrong commit to an Iceberg table; a secret leak or
  auth bypass; a crash, deadlock or table stuck in a non-idle state; a broken contract with
  OLake-UI, OLake-Helm, a deployed database or an optimizer of another version.
- **P1**: wrong behaviour on a realistic path (including every edge case in the design doc), a
  missing failure or restart path, a serious performance regression at realistic scale, a stated
  requirement with no test, or a test that would still pass if the feature broke.

## Independent reviewers

The author's context biases a review: it has already convinced itself the code is right. So the
review runs in **fresh subagents** that see only crafted context, never the session history:

- `design.md` and `test-plan.md` of the feature;
- `dev/sop/fusion-invariants.md` and this guide;
- the diff (base and head commits, or the patch) and permission to read any file in the repo.

Run one subagent per lens group, in parallel:

| Reviewer | Lenses |
| --- | --- |
| A. Data | 1, 2 |
| B. State and failure | 3, 4 |
| C. Contracts, security, performance | 5, 6, 7 |
| D. Tests vs design | 8, 9 |

For a diff under about 100 changed lines, one reviewer covering all lenses is enough. Each reviewer
returns findings in the format below with a confidence score. The coordinating session merges them,
removes duplicates and applies the filter.

## Lenses

1. **Table data.** Do rewrites produce exactly the live rows? Are deletes (equality and position)
   applied with correct sequence numbers? Partition spec and schema handled across evolution?
2. **Commits.** Validation against concurrent OLake snapshots; failure leaves the table unchanged;
   late or duplicate task results are not committed.
3. **State machine.** Every new path returns to `IDLE` or a retryable state; nothing stays in
   `PENDING`, `PLANNING` or `COMMITTING`; disabled or dropped tables stop cleanly.
4. **Failure and restart.** For each call that can throw or time out, what state is left; restart
   between any two statements; Postgres, object store or catalog errors; one bad table does not
   stop others.
5. **Contracts.** REST paths, fields, status codes and envelope; terminal API; table and catalog
   properties; config keys and env vars; Postgres migrations; thrift and task-property
   compatibility in both directions. Grep OLake-UI and OLake-Helm (paths in `CLAUDE.md` section 3).
6. **Security.** Secrets in logs, responses or metrics; auth on endpoints; input validation.
7. **Performance.** Per-file or per-table calls to the object store or Postgres, repeated full
   scans, unbounded memory, work done while holding a shared lock.
8. **Design conformance.** Every R, E and acceptance scenario implemented as written; nothing
   added that the doc did not ask for.
9. **Tests.** Each test would fail if the behaviour broke; edge cases from the doc are tested at
   the right level; no assertion was weakened to pass.

## Confidence and false-positive filter

Each finding gets a confidence from 0 to 100 that it is a real P0/P1 in this change:

- 100: proven, by a failing test, a reproduced run, or code that leaves no doubt.
- 75: very likely; the path is reachable and the impact clear, but not run.
- 50: plausible; depends on something not checked.
- 25 or less: speculative.

**Keep only findings with confidence 80 or more.** Before scoring, try to disprove the finding:
give at least two reasons it might be fine (handled by a caller, unreachable in Fusion's scope,
guarded by config or validation) and name the code that would settle it, then read that code.

Drop, whatever the score:

- issues on lines the change did not touch, unless the change makes them reachable or worse;
- issues that exist on the base branch and are unaffected by the change (mention them once, as
  pre-existing, outside the findings);
- anything a compiler, spotless or CI catches;
- style, naming, formatting and "consider refactoring" suggestions;
- behaviour the design doc explicitly asks for (raise it as a design question instead);
- out-of-scope modules (mixed formats, Hive, Paimon, Hudi, Flink, `amoro-web`).

## Finding format

```
[P0|P1] path:line - <problem in one sentence>
  Scenario: <concrete input and state that leads to the failure, and the wrong result>
  Evidence: <code read, test run, or log>  Confidence: <score>
  Fix: <smallest change that removes the problem>  Test: <test that would catch it>
```
