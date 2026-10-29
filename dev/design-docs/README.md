# Design docs: local working folders

Design docs are written and approved on **Confluence**. To develop a feature, export its page as
Markdown and save it here, one folder per feature:

```
dev/design-docs/<issue-number>-<short-slug>/design.md
```

Put this line first so the source is known:

```
<!-- source: <Confluence URL> | version or date: <page version or last-updated date> -->
```

The developer and the `/fusion-*` skills (see `dev/sop/README.md`) work from this file. The skills
also keep their working files in the same folder:

```
dev/design-docs/<issue-number>-<short-slug>/
  design.md       the design doc exported from Confluence (you add this)
  user-check.md   stages 1 and 7, /fusion-user-check
  test-plan.md    stage 2, /fusion-write-tests
  plan.md         stage 3, /fusion-implement
  verify.md       stage 4, /fusion-verify
  ai-review.md    stage 6, /fusion-pr-review
```

A skill can also take the Confluence URL or the Markdown directly and save `design.md` here for you.
When the Confluence page changes, export it again; Confluence stays the source of truth.

Everything in this folder except this README is git-ignored and never committed (design docs are
internal; the repository is public). `dev/sop/check-sop.sh --base` fails if any of it reaches a diff.
Check a feature folder at any stage with `dev/sop/check-sop.sh dev/design-docs/<folder>`.
