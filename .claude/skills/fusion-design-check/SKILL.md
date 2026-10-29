---
name: fusion-design-check
description: Checks a developer-written Fusion design doc (a Confluence page, read through a local snapshot in dev/design-docs/<folder>/design.md) against the SOP template and the real code, and reports blocking gaps - untestable requirements, missing edge cases, unlisted contract changes, claims the code contradicts. Can also interview the developer one question at a time to help them fill the doc. Stage 1 of the Fusion feature SOP. Use when the user asks to check, validate, review or start a Fusion design doc.
argument-hint: <Confluence URL | design doc .md | dev/design-docs/<issue>-<slug>> [interview]
---

# Fusion design check (SOP stage 1)

**Iron law: the developer is the author.** You find gaps and ask questions. You never decide
expected behaviour. You never edit the Confluence page; you write to the local `design.md` only text
the developer gave or approved, for them to put on Confluence.

Read first: `dev/sop/README.md`, `dev/sop/design-doc-template.md`, `dev/sop/architecture.md`,
`dev/sop/fusion-invariants.md`,
`CLAUDE.md` (scope, terminology, OLake-UI contracts).

## Input

`$ARGUMENTS`: a Confluence URL, a path to the design doc as Markdown, or the feature folder
`dev/design-docs/<issue>-<slug>/` (Markdown pasted in the chat also works). Get the snapshot as
`dev/sop/README.md` "Design doc input" says; the folder name comes from the doc's issue number
and title. If the page may have changed since the snapshot, refresh it first. Optionally followed by `interview`. No doc yet: copy the template to
`design.md`, fill only the header, and switch to interview mode.

## Interview mode (helping the developer write the doc)

1. Read the code the feature will touch before asking anything. Never ask what the code or
   `CLAUDE.md` already answers.
2. Ask **one question per message**, building on the last answer, in template order: problem and
   goals, user-facing behaviour, requirements, edge cases, design, compatibility. Offer a proposal the developer
   can correct when you have one ("I think E3 should skip the table; right?").
3. After each section, play it back in a few lines, then write it into the local `design.md` once
   the developer confirms, and remind them to copy it to the Confluence page. Mark anything they cannot decide yet as `[NEEDS CLARIFICATION: ...]`.
4. Before writing the design section, propose two or three approaches with trade-offs and a
   recommendation, and let the developer choose. Rejected ones go to "Alternatives rejected" in section 5.
5. When all sections are drafted, run the check below.

## Check

Copy this checklist and work through it:

```
- [ ] 1. Deterministic checks: dev/sop/check-sop.sh <folder>
- [ ] 2. Structure and testability
- [ ] 3. Ground every claim in the code
- [ ] 4. User-facing behaviour is product-doc ready
- [ ] 5. Contracts and compatibility
- [ ] 6. Edge cases: nothing left to discuss
- [ ] 7. Challenge each gap
```

1. Run `dev/sop/check-sop.sh <folder>`. Every FAIL in the design section is blocking.
2. Each requirement (R) has a Given / When / Then acceptance that is testable: input, expected
   outcome, where it is observed. Lite is used only for a change with no contract change; otherwise Full is required.
3. For every class, method, property, endpoint, state or table the doc names, find it (semantic
   search first, then grep) and confirm it exists and behaves as the doc assumes. Report
   contradictions with `path:line`. Check the design follows `architecture.md`: the right module
   and layer, and the existing mechanism of section 4 for each kind of change; a new mechanism needs
   a reason in the doc. Check "Core changes" against `architecture.md` section 6: every edit to
   core logic the design implies is listed, and an extension point or a small default-off hook was
   ruled out for a stated reason; propose the less invasive option when you see one. Check the
   design is the best available, not the first idea: "Alternatives rejected" has at least one real
   alternative with its trade-off, and nothing simpler or more reusable exists (an existing class,
   utility or Iceberg API; `architecture.md` section 7). If a better design exists, say so with the
   evidence; it is blocking. Name code the doc missed: other callers, other implementations
   of the interface, schedulers touching the same state. Stay in scope (Iceberg only).
4. Section 3 must let product write user docs without asking the developer: every setting has
   its UI name, property, default, allowed values and effect; every user-visible error has its
   message, cause and fix; limits are stated; no class names or internals.
5. For each row of section 6 (Compatibility), check whether the design really changes that contract, including rows
   marked `No change`. If OLake-UI / OLake-Helm paths are set in `CLAUDE.md` section 3, grep them for
   every changed name and report what breaks; if not set, say the check was skipped. Upgrade,
   downgrade and version skew between server and optimizer must be answered for Full docs.
6. Section 7 (Edge cases): all 16 standard rows answered (`check-sop.sh` enforces this). Then judge
   the answers: each says what happens, not "handled" or "should be fine"; an `N/A` reason is true
   for this feature; and every scenario specific to this feature (from the code paths it touches
   and `fusion-invariants.md`) has its own row from E17. The doc is READY only when no edge case is
   left to discuss.
7. For each gap, give two reasons it might not matter (handled elsewhere, out of scope, covered by
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

When the verdict is READY and the developer agrees, ask them to tick "1 Design approved" on the
Confluence page. Next step: `/fusion-write-tests <folder>`.
