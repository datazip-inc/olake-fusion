---
name: fusion-implement
description: Implements a Fusion feature from its approved design doc and red tests (dev/design-docs/<folder>/), first writing a resumable task plan (plan.md), then making each task's tests pass inside the existing architecture while checking dev/sop/fusion-invariants.md, never weakening the approved tests. Stage 3 of the Fusion feature SOP. Use when the user asks to implement a Fusion feature or fix that has a design doc and test plan.
argument-hint: <Confluence URL | design doc .md | dev/design-docs/<issue>-<slug>>
---

# Fusion implementation (SOP stage 3)

**Iron law: the approved tests are the spec.** Make them pass by changing production code. Never
edit their assertions, delete them, or mark them ignored. If a test is wrong, stop and show why;
the developer decides.

Read first: `dev/sop/architecture.md` (how Fusion is built and where each change goes), the feature's design doc (local `design.md` snapshot, fetched and kept fresh as
`dev/sop/README.md` "Design doc input" says) and `test-plan.md`, `dev/sop/fusion-invariants.md`,
`dev/sop/README.md` ("Commands used by the stages"), `CLAUDE.md` (scope, modules, repo rules).

## Input

The same input as `/fusion-write-tests` (Confluence URL, Markdown file or folder). Stop if `test-plan.md` is not approved or its tests are not Red:
run `/fusion-write-tests` first. If `<folder>/plan.md` exists, resume from its first unchecked task.

## 1. Plan (`<folder>/plan.md`)

Read the code you will change, its callers, other implementations of the same interface, and the
tests around it (semantic search first, then grep). Find the row of `architecture.md` section 4 for
each change (REST endpoint, config key, table property, scheduler, persistence, planner, executor,
thrift, metrics) and follow its named example; do not build a parallel mechanism. Respect the module
layering of section 3 and the coding rules of section 5.

Then write `plan.md`:

```
# Plan: <feature>

Architecture: <rows of architecture.md section 4 used; any new mechanism and why>
Core changes: <each edit to core logic (architecture.md section 6), or "None"; must match the design doc>
Invariants touched: <sections of fusion-invariants.md>
Server-side: <files>   Optimizer-side: <files>   (optimizer code must work with an older server and vice versa)

## Tasks
- [ ] Task 1: <one behaviour>
  Files: <path> (modify), ...
  Makes green: T1, T3
  Approach: <chosen way> - over <alternative considered>, because <reason from architecture.md section 7>
  Done when: <exact command> passes
```

- A task is the smallest change with its own tests that a reviewer could accept or reject. Setup
  and config fold into the task that needs them.
- The plan records decisions you cannot leave to the code (where, which pattern, which state). It
  is not the code: a plan longer than the change has written the code.
- No vague steps ("handle edge cases", "add validation"): name the edge case (E-ID) and the check.

If the design is wrong or incomplete for something you find (a caller it forgot, an unreachable
state), stop and tell the developer. The Confluence page is updated first and the snapshot
refreshed; do not silently deviate.

## 2. Implement, task by task

For each task:

1. Write the minimal code that makes its tests pass, beside the core where possible
   (`architecture.md` section 6). A core edit the design doc does not list under "Core changes":
   stop and ask; the developer updates the doc or you find an extension point. No speculative options, no drive-by refactors,
   renames or reformatting. Out-of-scope modules stay untouched; if shared code affects them, say
   so in one line.
2. Match the surrounding code: naming, logging with the table identifier, exceptions, shaded Guava
   (`org.apache.amoro.shade.guava32...`), config option style, comment density.
3. For every new line that can throw, answer: what state is left if it throws here, and after a
   restart here? Every failure ends in a defined state (back to `IDLE` or retryable; process marked
   failed; partial output harmless).
4. Keep the existing locking discipline for state shared with schedulers, the commit executor, REST
   handlers, optimizer callbacks or a second replica.
5. Schema changes go in a new `V<n>__*.sql` registered in `SchemaMigrator`. Contract changes are
   additive unless the design says otherwise; grep OLake-UI and OLake-Helm (`CLAUDE.md` section 3)
   for each changed name.
6. Check the task's code against the smell table in `architecture.md` section 7 (duplication,
   god methods, boolean mode flags, broad catches, unclosed iterables, static state, magic
   values, exposed mutable collections, speculative options) and fix any you find.
7. `test-compile`, run the task's tests, then the module's tests near the change. Fix code until
   green. Replace the stubs the task covers.
8. Tick the task in `plan.md` and mark its tests `Green` in `test-plan.md`.

After three failed attempts at the same task, stop: the approach or the design is likely wrong.
Report what you tried and what you learnt.

## 3. Finish

1. No stub left (`grep -rn '"not implemented"'` in touched modules). `spotless:apply` on each
   changed module. Apache-licensed files you modified carry `Modified by Datazip Inc. in <year>`.
2. Run all tests of each touched module, not only the new ones.
3. Self-challenge: give two reasons the overall approach might be wrong (a simpler existing
   mechanism, a case it handles badly) and say why you kept it or what you changed.

## Red flags: stop if you think any of these

| Thought | Reality |
| --- | --- |
| "The test is too strict, I'll relax it." | The test is the approved spec. Stop and ask. |
| "While I'm here I'll clean this up." | Out of scope. It costs review time and adds risk. |
| "My first idea works, ship it." | Compare it with one alternative and the existing code first (`architecture.md` section 7). |
| "The design doesn't say, I'll pick something." | Ask; the developer records the answer on Confluence. |
| "Tests pass, so it works." | Stage 4 still has to prove it. Do not claim done. |

## Output (in the chat)

Files changed with one line each on why; test results per module (passed / failed counts);
invariants touched; contract impact on OLake-UI / OLake-Helm; deviations from the design (should be
none, or agreed); what you could not verify. Ask the developer to tick "3 Implemented" on the Confluence page. Next
step: `/fusion-verify <folder>`.
