---
name: fusion-write-tests
description: Turns an approved Fusion design doc (Confluence page, local snapshot in dev/design-docs/<folder>/design.md) into a traced test plan, waits for developer approval, then writes unit, AMS service and integration tests that compile and fail for the right reason before any implementation exists. Stage 2 of the Fusion feature SOP. Use when the user asks to write tests or a test plan from a Fusion design doc.
argument-hint: <Confluence URL | design doc .md | dev/design-docs/<issue>-<slug>>
---

# Fusion test writing (SOP stage 2)

**Iron law: no production code before a failing test.** You write tests and compile-only stubs,
never the feature. A test you have not seen fail for the right reason proves nothing.

These tests stay in the final change: under the SOP they count as asked (`CLAUDE.md` section 5).

Read first: the feature's design doc (local `design.md` snapshot, fetched and kept fresh as
`dev/sop/README.md` "Design doc input" says), `dev/sop/test-plan-template.md`,
`dev/sop/fusion-invariants.md`, `dev/sop/README.md` ("Commands used by the stages").

## Input

`$ARGUMENTS`: a Confluence URL, a path to the design doc as Markdown, or the feature folder
`dev/design-docs/<issue>-<slug>/` (Markdown pasted in the chat also works). Get the snapshot as
`dev/sop/README.md` "Design doc input" says; the folder name comes from the doc's issue number
and title. If the page may have changed since the snapshot, refresh it first.

Run `dev/sop/check-sop.sh <folder>` first; if the design checks
fail (open `[NEEDS CLARIFICATION]`, missing sections), stop and send the developer to
`/fusion-design-check`.

## Progress

```
- [ ] 1. Test plan written from the template
- [ ] 2. Developer approved the plan          <- hard stop
- [ ] 3. Tests and stubs written
- [ ] 4. Every test red for the right reason
- [ ] 5. check-sop.sh traceability passes
```

## 1. Test plan

Write `<folder>/test-plan.md` from `dev/sop/test-plan-template.md`.

- Every requirement (R) and every edge case (E) not marked N/A appears in the traceability table: covered by a test,
  or listed with the reason and how it is checked instead (usually the stage 7 manual test).
- Add tests for invariants the change touches even if the doc forgot them, and tell the developer
  so the doc can be updated.
- Choose levels with the table in `dev/sop/architecture.md` section 8: classify each change
  (pure logic, runtime and scheduling, REST field, persistence, migration, rewrite or commit, config,
  compatibility) and plan **at least** the levels its row requires; a change in several rows needs
  all of them. Beyond the minimum, add the cheapest level that proves the behaviour. AMS service
  tests use the existing bases and Iceberg table helpers, not mixed-format ones; they run on Derby,
  so Postgres-specific SQL and migrations need the integration suite or a Postgres runtime check.
  Integration tests are slow: few, focused, tagged `serial` when they need exclusive table state.
- In the plan, add a "Levels" line per change: `<change> - <row of section 8> - <levels planned>`.
- One behaviour per test, named after the behaviour. Assert what users or the system rely on
  (rows, files, deletes, states, API fields, persisted rows), not call counts, unless the call is
  the contract. Use the exact values from the design doc.

Then **stop and ask the developer to approve the plan**. Apply their changes. No test code before
approval.

## 2. Tests

1. Put each test next to its neighbours and match them: JUnit 4 or 5 as the module uses, helpers,
   naming (`Test<Thing>`, `*IT`). Reuse existing bases and helpers before writing new ones.
2. For types or methods that do not exist yet, add the smallest stub with the signature from the
   design doc and a body `throw new UnsupportedOperationException("not implemented");`. List each
   in the plan's "Stubs for stage 3". `check-sop.sh --base` fails while any stub remains.
3. Modified Apache-licensed files get `Modified by Datazip Inc. in <year>`; new test files copy the
   header of a neighbouring Datazip-added file.

## 3. Prove the tests are meaningful

1. `test-compile` the touched modules.
2. Run the new tests with `-Dtest=...`. Each must fail **because the feature is missing**: the stub
   throws, or the asserted behaviour is absent. A setup error, a wrong assumption about existing
   behaviour, or a compile error is not a valid red; fix the test.
3. A test that already passes: either the behaviour exists (tell the developer; the design may be
   wrong) or the test does not test what it claims (fix it).
4. Integration tests: compile them; run them only if the developer asks.
5. Mark each test `Red` in the plan with its failure message, run `spotless:apply` on each changed
   module, and run `dev/sop/check-sop.sh <folder>`.

## Red flags: stop if you think any of these

| Thought | Reality |
| --- | --- |
| "This is too simple to test." | Simple code breaks too; the test costs a minute. |
| "I'll implement a little so the test is realistic." | That is stage 3. Stubs only. |
| "This edge case is hard to test, I'll skip it." | List it under "not covered" with how it is checked instead. |
| "The test passes already, good." | A test that never failed proves nothing. |

## Output (in the chat)

Path of `test-plan.md`, tests added (`Class#method`, level, covers), stubs, the red result of each
test, anything not covered and why, and the `check-sop.sh` result. Ask the developer to tick
"2 Test plan approved" on the Confluence page. Next step: `/fusion-implement <folder>`.
