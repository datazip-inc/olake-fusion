---
name: fusion-write-tests
description: Stage 2 of the Fusion feature SOP. Turns a design doc (dev/design-docs/<folder>/design.md) into a test plan traced to its requirements and edge cases, waits for developer approval, then writes the tests (unit, AMS service, integration) so they compile and fail for the right reason before any implementation. Use when the user asks to write tests or a test plan from a Fusion design doc.
argument-hint: dev/design-docs/<issue>-<slug>
---

# Fusion test writing (SOP stage 2)

Tests are written from the design doc, before the implementation, and stay in the final change.
This is the "asked" case of `CLAUDE.md` section 5: under the SOP, tests belong in the diff.

Read first: the feature's `design.md`, `dev/sop/README.md`, `dev/sop/fusion-invariants.md`,
`CLAUDE.md` section 5.

## Input

`$ARGUMENTS` is the feature folder. If `design.md` has open questions that block a requirement, stop
and list them; run `/fusion-design-check` first.

## Step 1: Test plan

Write `<folder>/test-plan.md`:

```
# Test plan: <feature>

Design doc: design.md (<last updated date>)

| ID | Covers | Level | Test (class#method) | Given / When / Then |
| --- | --- | --- | --- | --- |
| T1 | R1 | unit | TestFoo#rejectsNegativeSize | ... |

## Not covered by automated tests
- <requirement / edge case> - why, and how it is covered instead (manual test in stage 5)
```

Rules:

- Every requirement (R*) and edge case (E*) appears in `Covers` at least once, or in "Not covered"
  with a reason. Add tests for invariants the change touches even if the doc forgot them, and tell
  the developer so the doc can be updated.
- Pick the cheapest level that really proves the behaviour:
  - **unit**: pure logic in `amoro-common`, `amoro-format-iceberg` (evaluators, planners, executors),
    `amoro-optimizer-*`. Look at neighbours, for example `amoro-format-iceberg/src/test/.../optimizing`.
  - **AMS service test**: scheduling, table runtime, persistence, REST controllers in `amoro-ams`,
    using the existing bases (`AMSServiceTestBase`, `AMSTableTestBase`, `ExecutorTestBase`,
    `TableCatalogTestBase`). Use Iceberg table helpers, not mixed-format ones.
  - **integration** (`integration-tests/`, `io.olake.fusion.it`, base `ItBase`, names in
    `Contract`): only for behaviour that needs the real stack (Spark optimizer, OLake writes, end-to-end
    compaction). They need Docker and are slow; keep them few.
- Each test checks one behaviour, with a name that says it. Assert outcomes users or the system rely
  on (rows, files, deletes, status, API fields), not internal call counts, unless the call itself is
  the contract.

Then **stop and ask the developer to approve the plan.** Apply their changes to `test-plan.md`. Do
not write test code before approval.

## Step 2: Tests

1. Put each test where its neighbours live, matching the module's framework (JUnit 4 or 5, whichever
   the surrounding tests use), helpers and naming (`Test<Thing>` for unit, `*IT` for integration).
   Reuse existing helpers and bases before adding new ones.
2. If a test needs a type, method or property that does not exist yet, add the smallest stub that
   compiles, with the signature from the design doc and a body that throws
   `UnsupportedOperationException("not implemented")`. List every stub in `test-plan.md` under
   `## Stubs for stage 3`.
3. Do not implement the feature. Do not change production behaviour beyond stubs.
4. Apache-licensed files you modify get `Modified by Datazip Inc. in <year>` (see `CLAUDE.md`). New
   test files copy the header style of a neighbouring Datazip-added file.

## Step 3: Prove the tests are meaningful

Commands are in `dev/sop/README.md`, "Commands used by the stages".

1. Compile the touched modules with `test-compile`.
2. Run the new unit and service tests with `-Dtest=<Class>`. Each must **fail because the feature
   is missing** (the stub throws, or the
   asserted behaviour is absent), never because of a setup error, a wrong assumption about existing
   behaviour, or a compile error.
3. Tests that pass already: either the behaviour exists (tell the developer; the design may be
   wrong) or the test does not test what it claims (fix it).
4. Integration tests: compile them. Run them only if the developer asks (`make integration-test
   TEST_ONLY=<Class#method>`; needs Docker and takes long).
5. Run `spotless:apply` on each module you changed.

## Output

Report in the chat: path of `test-plan.md`, the tests added (class#method, level, covers), the stubs,
and the run result per test (expected failure and its message). Anything not covered and why. Next
step: `/fusion-implement <folder>`.
