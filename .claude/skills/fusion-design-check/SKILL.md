---
name: fusion-design-check
description: Stage 1 of the Fusion feature SOP. Checks a developer-written design doc (dev/design-docs/<folder>/design.md) against the code and the SOP template, and reports gaps - missing edge cases, untouched contracts, wrong assumptions - before tests are written. Reports only; never rewrites the doc. Use when the user asks to check, validate or review a Fusion design doc.
argument-hint: dev/design-docs/<issue>-<slug>
---

# Fusion design check (SOP stage 1)

You check a design doc written by a developer. The developer stays the author: you report gaps, you
do not edit `design.md` unless the developer asks you to apply a specific fix.

Read first: `dev/sop/README.md` (the SOP), `dev/sop/design-doc-template.md`,
`dev/sop/fusion-invariants.md`, and `CLAUDE.md` (scope, terminology, OLake-UI contracts).

## Input

`$ARGUMENTS` is the feature folder or its `design.md`. If it is missing, list the folders under
`dev/design-docs/` and ask which one.

## Steps

1. **Structure.** Every template section is present and filled in, or says `N/A` with a reason.
   Requirements and edge cases have IDs (R1.., E1..). Each requirement is testable (input, expected
   outcome, where it is observed). Open questions that block a requirement are blocking.

2. **Ground the design in the code.** For every class, method, property, endpoint or table named in
   the doc, find it (semantic search first, then grep for exact symbols) and confirm it exists and
   behaves as the doc assumes. Note where the code contradicts the doc. Name code the doc should have
   mentioned but did not (callers, other implementations of an interface, a scheduler that touches
   the same state). Stay in scope: Iceberg only, no mixed/Hive/Paimon/Hudi/Flink.

3. **Contracts.** For each row of section 6, check whether the design really changes it. If the
   change touches a REST endpoint, property, config key or env var and the OLake-UI / OLake-Helm
   paths are set in `CLAUDE.md` section 3, grep them for usages and report what would break. If
   the paths are not set, say the check was skipped and ask the developer for them.

4. **Edge cases.** Walk the list in template section 7 and the invariants file. For each scenario
   that applies to this change and is not covered, propose one line: scenario and the question the
   developer must answer. Do not invent the expected behaviour; that is the developer's decision.

5. **Self-challenge.** For each gap, ask why it might not matter (already handled elsewhere in the
   code, impossible given the scope, covered by another section). Drop gaps you disprove.

## Output

Reply in the chat, not in a file:

```
Design check: <folder>
Verdict: READY | NOT READY (<n> blocking)

Blocking (must fix before /fusion-write-tests)
- [section] <gap> - <evidence: file:line or doc quote> - <question for the developer>

Non-blocking (worth adding)
- ...

Code the doc should reference
- path:line - why

Checks skipped
- ...
```

Blocking means: a requirement that cannot be tested, a missing edge case that touches an invariant,
a contract change not listed, or a doc claim the code contradicts. Keep the list short and concrete;
no style comments on the prose.
