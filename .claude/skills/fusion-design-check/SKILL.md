---
name: fusion-design-check
description: Checks a developer-written Fusion design doc (dev/design-docs/<folder>/design.md) against the SOP template and the real code, and reports blocking gaps - untestable requirements, missing edge cases, unlisted contract changes, claims the code contradicts. Can also interview the developer one question at a time to help them fill the doc. Stage 1 of the Fusion feature SOP. Use when the user asks to check, validate, review or start a Fusion design doc.
argument-hint: dev/design-docs/<issue>-<slug> [interview]
---

# Fusion design check (SOP stage 1)

**Iron law: the developer is the author.** You find gaps and ask questions. You never decide
expected behaviour, and you change `design.md` only with text the developer gave or approved.

Read first: `dev/sop/README.md`, `dev/sop/design-doc-template.md`, `dev/sop/fusion-invariants.md`,
`CLAUDE.md` (scope, terminology, OLake-UI contracts).

## Input

`$ARGUMENTS`: the feature folder, optionally followed by `interview`. No folder: list
`dev/design-docs/` and ask. No `design.md` yet: copy the template there, fill only the header, and
switch to interview mode.

## Interview mode (helping the developer write the doc)

1. Read the code the feature will touch before asking anything. Never ask what the code or
   `CLAUDE.md` already answers.
2. Ask **one question per message**, building on the last answer, in template order: problem,
   goals and non-goals, stories, requirements, edge cases, contracts. Offer a proposal the developer
   can correct when you have one ("I think E3 should skip the table; right?").
3. After each section, play it back in a few lines, then write it into `design.md` once the
   developer confirms. Mark anything they cannot decide yet as `[NEEDS CLARIFICATION: ...]`.
4. Before writing the design section, propose two or three approaches with trade-offs and a
   recommendation, and let the developer choose. Rejected ones go to "Alternatives considered".
5. When all sections are drafted, run the check below.

## Check

Copy this checklist and work through it:

```
- [ ] 1. Deterministic checks: dev/sop/check-sop.sh <folder>
- [ ] 2. Structure and testability
- [ ] 3. Ground every claim in the code
- [ ] 4. Contracts and compatibility
- [ ] 5. Edge cases
- [ ] 6. Challenge each gap
```

1. Run `dev/sop/check-sop.sh <folder>`. Every FAIL in the design section is blocking.
2. Each requirement (R) and acceptance scenario is testable: input, expected outcome, where it is
   observed. Lite is used only for a change with no contract change; otherwise Full is required.
3. For every class, method, property, endpoint, state or table the doc names, find it (semantic
   search first, then grep) and confirm it exists and behaves as the doc assumes. Report
   contradictions with `path:line`. Name code the doc missed: other callers, other implementations
   of the interface, schedulers touching the same state. Stay in scope (Iceberg only).
4. For each row of section 8, check whether the design really changes that contract, including rows
   marked `No change`. If OLake-UI / OLake-Helm paths are set in `CLAUDE.md` section 3, grep them for
   every changed name and report what breaks; if not set, say the check was skipped. Upgrade,
   downgrade and version skew between server and optimizer must be answered for Full docs.
5. Walk the prompts of template section 9 and the sections of `fusion-invariants.md`. For each
   scenario that applies and is missing, write the question the developer must answer.
6. For each gap, give two reasons it might not matter (handled elsewhere, out of scope, covered by
   another section) and check them in the code. Drop gaps you disprove.

## Output (in the chat)

```
Design check: <folder>
Verdict: READY | NOT READY (<n> blocking)

Blocking
- [section] <gap> - <evidence: path:line or doc quote> - Question: <for the developer>

Non-blocking
- ...

Code the doc should reference
- path:line - why

Skipped checks
- ...
```

When the verdict is READY and the developer agrees, tick "1 Design approved" in the doc's SOP
progress. Next step: `/fusion-write-tests <folder>`.
