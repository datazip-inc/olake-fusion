---
name: fusion-pr-review
description: Gives a Fusion pull request or branch an independent second review - checks the SOP gates with dev/sop/check-sop.sh, then runs reviewer subagents against the design doc and dev/sop/fusion-invariants.md and reports only confirmed P0/P1 findings. Does not post to GitHub unless asked. Stage 7 of the Fusion feature SOP. Use when the user asks to review a Fusion PR, a branch, or someone else's change.
argument-hint: <PR number | branch>
---

# Fusion PR review (SOP stage 7)

**Iron law: form your own view.** `ai-review.md` and the PR description are the author's claims.
Check them; do not inherit them.

Read first: `dev/sop/README.md` (gates), `dev/sop/review-guide.md`, `dev/sop/fusion-invariants.md`,
`CLAUDE.md`.

## Input

`$ARGUMENTS`: a PR number or a branch.

- PR: `gh pr view <n> --json number,title,body,baseRefName,headRefName,headRefOid,files` and
  `gh pr diff <n>`.
- Branch: the diff against `origin/staging`.

Read changed files in full where the diff is not enough. To run anything, check the branch out in a
separate worktree; never change the author's branch.

## 1. SOP gates

Run `dev/sop/check-sop.sh <feature folder> --base origin/<base branch>` on the PR head, then check
by hand:

- Targets `staging`; title in Conventional Commits form.
- Feature folder present with `design.md`, `test-plan.md`, `ai-review.md`, unless the change is
  trivial as the SOP defines it.
- PR description has the demo video (or a terminal recording for a pure refactor) and the
  documentation section. You cannot judge the video; tell the human reviewer what it must show.
- No leftover test-only changes (`local-test/`, compose files, debug logging, disabled tests).

A failed gate is P1.

## 2. Review

Follow `dev/sop/review-guide.md`: reviewer subagents in parallel by lens group, given only the
design doc, test plan, invariants, review guide and the diff. Merge, deduplicate, apply the
confidence threshold (80) and the false-positive filter.

Then confirm each surviving finding yourself: read the code that proves it, or reproduce it in the
separate worktree (tests from `dev/sop/README.md` "Commands used by the stages"). Drop what you
cannot confirm.

## Output (in the chat)

```
PR review: #<n> <title>
Verdict: APPROVE | REQUEST CHANGES

SOP gates
- [pass|fail] ...

Findings (confirmed, P0 first)
- [P0] path:line - <problem>
  Scenario: ...  Evidence: ...  Fix: ...  Test: ...

For the human reviewer
- Design: <one or two questions about whether this is the right approach>
- Demo video: confirm it shows <behaviour>.
```

Post to GitHub only when the user asks, and only the findings they choose: inline comments through
`gh api repos/{owner}/{repo}/pulls/<n>/comments` (anchored to `headRefOid`), and the summary with
`gh pr review <n> --comment` or `--request-changes`.
