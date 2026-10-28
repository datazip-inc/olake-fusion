---
name: fusion-pr-review
description: Stage 7 of the Fusion feature SOP. Independent second review of a Fusion pull request against its design doc, the SOP gates and dev/sop/fusion-invariants.md, reporting only P0/P1 findings that survive self-challenge. Does not post to GitHub unless asked. Use when the user asks to review a Fusion PR or branch as the second reviewer.
argument-hint: <PR number | branch>
---

# Fusion PR review (SOP stage 7)

You are the second reviewer. Form your own view from the design doc and the code; do not trust
`ai-review.md` or the PR description, use them only to see what the author claims and check it.

Read first: `dev/sop/README.md` (gates, severity), `dev/sop/fusion-invariants.md`, `CLAUDE.md`.

## Input

`$ARGUMENTS` is a PR number or a branch. For a PR: `gh pr view <n> --json title,body,baseRefName,headRefName,files`
and `gh pr diff <n>`. For a branch: diff against `origin/staging`. Read the changed files in full
where the diff is not enough to judge.

## Step 1: SOP gates

Check and list as pass / fail (a fail is P1 unless noted):

- PR targets `staging`; title follows Conventional Commits.
- Feature folder exists in the diff with `design.md`, `test-plan.md`, `ai-review.md` (not required
  for trivial changes, see the SOP).
- `ai-review.md` says `Open P0/P1: 0`.
- Each requirement in `design.md` has a test in `test-plan.md`, and those tests are in the diff.
- PR description has the demo video (or a terminal recording for a pure refactor) and the
  documentation section. You cannot judge the video's content; say the human reviewer must.
- Modified Apache-licensed files carry `Modified by Datazip Inc. in <year>`.
- No temporary code: stubs, debug logging, disabled tests, local-test or compose changes made only
  for testing.

## Step 2: Review

Same order and severity as stage 4, done independently:

1. Does the code do what `design.md` says, for every requirement and edge case? Anything in the code
   that the doc does not ask for?
2. Invariants touched (`fusion-invariants.md`): table data correctness, commit conflicts with OLake,
   state machine exits, restart and retry, optimizer/server compatibility, migrations.
3. Failure paths and concurrency.
4. Contracts: if paths of OLake-UI / OLake-Helm are set in `CLAUDE.md` section 3, grep them for
   every changed endpoint, field, property, config key or env var.
5. Security and performance.
6. Tests: would they fail if the feature broke? Edge cases from the doc missing?

Optionally check out the branch in a separate worktree and compile and run the touched modules'
tests (commands in `dev/sop/README.md`) to confirm a suspicion. Do not modify the branch.

For every finding, try to disprove it (at least two reasons it might be fine, and the evidence that
would change your mind). Keep only what survives. Rank P0 before P1. No style, naming or formatting
comments.

## Output

In the chat:

```
PR review: #<n> <title>
Verdict: APPROVE | REQUEST CHANGES

SOP gates
- [pass|fail] ...

Findings
- P0 path:line - <problem>. <why it breaks, concrete scenario>. <suggested fix>.
- P1 ...

For the human reviewer
- Is this the right design? <one or two questions worth asking>
- Demo video: confirm it shows <the behaviour to look for>.
```

Post comments to GitHub only when the user asks (`gh pr review` / `gh api` for inline comments),
and only the findings they choose.
