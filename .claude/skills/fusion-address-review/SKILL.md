---
name: fusion-address-review
description: Works through review comments on a Fusion pull request - verifies each comment against the code and design doc before acting, fixes valid ones one at a time with a test, pushes back with evidence on wrong ones, routes behaviour changes back through the design doc, and drafts thread replies. Use when the user asks to address, resolve, handle or respond to PR review comments on a Fusion change.
argument-hint: <PR number>
---

# Fusion address review (SOP stage 7, author side)

**Iron law: verify before implementing.** A review comment is a claim to check, not an order. Fix
what is right, push back on what is wrong, ask about what is unclear, and never change behaviour the
design doc defines without updating the doc first.

Read first: the feature's `design.md` and `test-plan.md`, `dev/sop/review-guide.md`,
`dev/sop/fusion-invariants.md`.

## Input

`$ARGUMENTS`: the PR number. Fetch the unresolved comments:
`gh api repos/{owner}/{repo}/pulls/<n>/comments` (inline) and `gh pr view <n> --comments` (general).

## 1. Triage (before changing anything)

For each comment, restate what it asks in one line, then check it against the code, the design doc
and the invariants. Classify it:

| Class | Meaning | Action |
| --- | --- | --- |
| Valid | Real defect or gap in this change | Fix (step 2) |
| Design | Asks for behaviour different from `design.md` | Developer decides; update the doc, then rerun the affected stages |
| Wrong | Contradicted by code, tests or the design | Push back with evidence |
| Unclear | Cannot tell what is asked | Ask one specific question |
| Out of scope | Valid, but not this change | Propose a follow-up issue |

Show the triage table to the developer and wait for their go-ahead. If any comment is Unclear,
implement nothing that depends on it until it is answered.

## 2. Fix valid comments, one at a time

For each: write or extend a test that fails without the fix, make the smallest fix, run the test and
the module's tests, and record the red-green proof. After all fixes, run
`dev/sop/check-sop.sh <folder> --base origin/staging` and the full suites of the touched modules. If
the fixes are more than trivial, rerun `/fusion-verify` so `ai-review.md` stays true.

## 3. Draft replies

One reply per thread, in that thread, never as a new top-level comment:

- Fixed: what changed and where (`path:line`, commit), and the test that covers it.
- Pushback: the technical reason with evidence (code, test, design doc section). No defensiveness.
- Question: the one specific thing you need to know.

No "Great point!", "You're absolutely right!" or thanks: state the fix or the reason. If your
earlier pushback turns out wrong, say so in one sentence and fix it.

Post replies only after the developer approves them:
`gh api repos/{owner}/{repo}/pulls/<n>/comments/<comment id>/replies -f body=...`.

## Output (in the chat)

The triage table, the fixes with test evidence, the drafted replies, and anything waiting on the
developer or the reviewer.
