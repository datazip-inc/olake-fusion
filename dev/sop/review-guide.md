# Review guide

Used by `/fusion-pr-review`, the one AI review (stage 6, on the raised PRs), by
`/fusion-address-review`, and by the human reviewer in stage 8, who applies the same severity, lenses and finding format without
subagents.

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
  requirement with no test, or a test that would still pass if the feature broke. Also P1: a smell
  from the table in `dev/sop/architecture.md` section 7, or a clearly better implementation that
  already exists (an existing utility, an Iceberg API, a simpler design), shown concretely.

## Independent reviewers

The author's context biases a review: it has already convinced itself the code is right. So the
review runs in **fresh subagents** that see only crafted context, never the session history:

- the local `design.md` snapshot of the Confluence design doc and `test-plan.md`;
- `dev/sop/architecture.md`;
- `dev/sop/fusion-invariants.md` and this guide;
- the diff (base and head commits, or the patch) and permission to read any file in the repo.

Run one subagent per lens group, in parallel:

| Reviewer | Lenses |
| --- | --- |
| A. Data | 1, 2 |
| B. State and failure | 3, 4 |
| C. Contracts, security, performance | 5, 6, 7 |
| D. Design, architecture, quality and tests | 8, 9, 10, 11 |

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
8. **Design conformance.** Every requirement and edge case implemented as written; nothing
   added that the doc did not ask for.
9. **Tests.** Each test would fail if the behaviour broke; edge cases from the doc are tested; no
   assertion was weakened to pass. Every change has at least the levels `architecture.md`
   section 8 requires (unit, AMS service, integration); a missing required level is P1, for example
   a new REST field with no `Contract` update and integration assertion, or a migration tested only
   on Derby.
10. **Architecture.** The change follows `dev/sop/architecture.md`: code in the right module and
   layer (no optimizer dependency on `amoro-ams`), the existing mechanism for its kind of change
   (section 4) instead of a parallel one, the coding rules of section 5, and minimal core change
   (section 6). A deviation is P1 when it breaks layering, duplicates a mechanism, or changes core
   logic that the design doc does not list under "Core changes" or that an extension point could
   have avoided; otherwise it is not reported.
11. **Quality and better implementation.** Check new code against `architecture.md` section 7: SOLID,
   DRY, KISS, error handling, resources, testability, and its smell table. For each piece of new
   logic ask: does the codebase or Iceberg already do this, and is there a simpler design that
   meets the same requirements? Report only concrete cases: the principle or smell, the location,
   and the better code (name the existing method or API, or sketch the simpler structure).

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
- formatting, and naming unless the name misleads;
- refactoring of code the change did not touch;
- quality opinions without a named principle or smell and a concrete better alternative;
- behaviour the design doc explicitly asks for (raise it as a design question instead);
- out-of-scope modules (mixed formats, Hive, Paimon, Hudi, Flink, `amoro-web`).

## Finding format

```
[P0|P1] path:line - <problem in one sentence>
  Scenario: <concrete input and state that leads to the failure, and the wrong result>
  Evidence: <code read, test run, or log>  Confidence: <score>
  Fix: <smallest change that removes the problem>  Test: <test that would catch it>
```
