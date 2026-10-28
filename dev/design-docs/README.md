# Design docs

One folder per feature, created in stage 1 of the [feature SOP](../sop/README.md):

```
dev/design-docs/<issue-number>-<short-slug>/
  design.md       stage 1, developer, from ../sop/design-doc-template.md
  test-plan.md    stage 2, /fusion-write-tests, from ../sop/test-plan-template.md
  plan.md         stage 3, /fusion-implement, the resumable task list
  ai-review.md    stage 4, /fusion-verify, evidence and the manual test script
```

The folder is committed with the change and kept after merge as the record of why the change was
made. Check a folder at any stage with `dev/sop/check-sop.sh dev/design-docs/<folder>`.
