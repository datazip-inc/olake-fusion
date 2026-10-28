---
name: fusion-implement
description: Stage 3 of the Fusion feature SOP. Implements a feature from its design doc (dev/design-docs/<folder>/design.md) inside Fusion's existing architecture, checks every change against dev/sop/fusion-invariants.md, and iterates until all stage 2 tests pass, without weakening those tests. Use when the user asks to implement a Fusion feature or fix that has a design doc and test plan.
argument-hint: dev/design-docs/<issue>-<slug>
---

# Fusion implementation (SOP stage 3)

Read first: the feature's `design.md` and `test-plan.md`, `dev/sop/fusion-invariants.md`,
`CLAUDE.md` (scope, modules, terminology, repo rules).

## Input

`$ARGUMENTS` is the feature folder. Stop if `test-plan.md` is missing or the stage 2 tests do not
exist yet: run `/fusion-write-tests` first.

## Before writing code

1. Build a short plan in the chat: for each requirement (R*), the file and method that will change
   and the test (T*) that proves it. Mark server-side and optimizer-side code (see `CLAUDE.md`
   section 1); optimizer-side code must stay compatible with an older server and the other way round.
2. Read the code you will change and its callers, other implementations of the same interface, and
   tests around it. Use semantic search first, then grep for exact symbols. Find the existing
   pattern for what you are about to add (a config option, a property, a mapper method, a REST
   handler, a scheduler) and copy it; do not invent a parallel mechanism.
3. Walk `fusion-invariants.md` and note which sections this change touches. Those are the places
   to be careful, and the places stage 4 will look first.
4. If the design is wrong or incomplete for something you find (a caller it forgot, a state it
   cannot reach), stop and tell the developer. The doc is updated first; do not silently deviate.

## Writing code

- Minimal diff: only what the design needs. No drive-by refactors, renames or reformatting of code
  you did not change. Out-of-scope modules stay untouched; if shared code affects them, say so in
  one line.
- Match the surrounding code: naming, logging (with the table identifier), exceptions, comment
  density, Guava shading (`org.apache.amoro.shade.guava32...`), config option style.
- Every failure path ends in a defined state: a table runtime returns to `IDLE` or a retryable
  state, a process is marked failed, a partial write is cleaned up or harmless. Ask yourself for each
  new line that can throw: what state is left if it throws here, and after a restart here?
- Concurrency: identify which threads touch the state you change (schedulers, commit executor,
  REST handlers, optimizer callbacks, a second replica) and keep the existing locking discipline.
- Persistence: schema changes go in a new `V<n>__*.sql` migration registered in `SchemaMigrator`.
- Contracts: additive and backward compatible unless the design doc says otherwise. If you touch an
  endpoint, property, config key or env var, grep OLake-UI and OLake-Helm (paths in `CLAUDE.md`
  section 3) and report what changes there.
- Replace every stub listed in `test-plan.md` under "Stubs for stage 3". None may remain.
- Apache-licensed files you modify get `Modified by Datazip Inc. in <year>` within the first 40 lines.

## Loop

Commands are in `dev/sop/README.md`, "Commands used by the stages".

1. `test-compile` the touched modules.
2. Run the stage 2 tests (`-Dtest=...`) and then the module's existing tests near your change.
3. On failure, fix the **code**. You may not change a stage 2 test's assertions, delete it, or mark it
   ignored. If you believe a test is wrong, stop and explain why with evidence; the developer
   decides and updates `test-plan.md`.
4. Repeat until all stage 2 tests pass and nearby existing tests still pass.
5. `spotless:apply` on each changed module.

## Self-challenge before handing over

For the approach as a whole, give two reasons it might be the wrong approach (a simpler existing
mechanism, a case it handles badly) and say why you kept it or what you changed.

## Output

In the chat: files changed with one line each on why, the test results (passed / failed counts per
module), invariants touched, contract impact on OLake-UI / OLake-Helm, deviations from the design
doc (should be none, or agreed), and anything you could not verify. Next step:
`/fusion-verify <folder>`.
