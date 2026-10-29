#!/usr/bin/env bash
#
# Deterministic checks for a feature folder of the feature SOP (dev/sop/README.md).
#
#   dev/sop/check-sop.sh <feature folder> [--base <git ref>]
#
# Prints PASS / FAIL / SKIP per check and exits 1 if any check fails. Checks whose input does not
# exist yet (for example ai-review.md before stage 6) are skipped, so the script can run at any
# stage. With --base, it also checks the diff against that ref (stubs, modification headers).
# It only reads; it never changes files or git state.

set -uo pipefail

usage() {
  echo "usage: $0 <dev/design-docs/<folder>> [--base <git ref>]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
folder="${1%/}"
shift
base=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="${2:-}"; [[ -n "$base" ]] || usage; shift 2 ;;
    *) usage ;;
  esac
done

[[ -d "$folder" ]] || { echo "Not a folder: $folder" >&2; exit 2; }
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
design="$folder/design.md"
plan="$folder/test-plan.md"
review="$folder/ai-review.md"
failures=0

# The design doc without HTML comments: template guidance lives in comments and is not content.
doc="$(mktemp)"
trap 'rm -f "$doc"' EXIT
[[ -f "$design" ]] && perl -0pe 's/<!--.*?-->//gs' "$design" >"$doc"

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }
skip() { printf 'SKIP  %s\n' "$1"; }
detail() { printf '        %s\n' "$@"; }

# Design doc IDs that need a test: requirement rows (| R1 |) and edge case rows (| E1 |). Rows struck
# through (~~R3~~) were removed; edge cases answered "N/A - <reason>" do not apply.
design_ids() {
  grep -E '^\| *(R|E)[0-9]+ *\|' "$doc" | grep -v '~~' | grep -vE '\| *N/A[^|]*\| *$' |
    sed -E 's/^\| *((R|E)[0-9]+).*/\1/' | sort -u
}

# 1. Design doc present and complete.
if [[ ! -f "$design" ]]; then
  fail "design.md exists"
else
  pass "design.md exists"

  lite=0
  # Doc type is the fourth cell of the header row.
  grep -qE '^\|[^|]*\|[^|]*\|[^|]*\| *Lite *\|' "$doc" && lite=1
  if [[ $lite -eq 1 ]]; then
    required=("Summary" "Problem and goals" "User-facing behaviour" "Requirements" "Edge cases"
      "Testing and rollout" "Open questions")
  else
    required=("Summary" "Problem and goals" "User-facing behaviour" "Requirements" "Design"
      "Compatibility" "Edge cases" "Non-functional" "Testing and rollout" "Open questions")
  fi
  missing=()
  for section in "${required[@]}"; do
    grep -qE "^## [0-9]+\. ${section}" "$doc" || missing+=("$section")
  done
  if [[ ${#missing[@]} -eq 0 ]]; then
    pass "design.md has every required section ($([[ $lite -eq 1 ]] && echo Lite || echo Full))"
  else
    fail "design.md has every required section"
    detail "${missing[@]/#/missing: }"
  fi

  # Template placeholders left in the doc.
  placeholders="$(grep -nE '<(Feature title|number|developer|names|YYYY-MM-DD)>|\| *Full / Lite *\||^\| *R1 *\|( *\|)+ *$' "$doc")"
  if [[ -z "$placeholders" ]]; then
    pass "design.md has no template placeholders"
  else
    fail "design.md has no template placeholders"
    detail "$(echo "$placeholders" | head -5)"
  fi

  clarifications="$(grep -n '\[NEEDS CLARIFICATION:' "$doc")"
  if [[ -z "$clarifications" ]]; then
    pass "design.md has no open [NEEDS CLARIFICATION]"
  else
    fail "design.md has no open [NEEDS CLARIFICATION]"
    detail "$(echo "$clarifications" | head -5)"
  fi

  # Edge cases: the 16 standard rows must all be there, and no row may be left unanswered.
  missing_edges=()
  for i in $(seq 1 16); do
    grep -qE "^\| *(~~)?E${i}(~~)? *\|" "$doc" || missing_edges+=("E$i")
  done
  if [[ ${#missing_edges[@]} -eq 0 ]]; then
    pass "design.md keeps the 16 standard edge cases"
  else
    fail "design.md keeps the 16 standard edge cases"
    detail "missing: ${missing_edges[*]}"
  fi
  unanswered="$(grep -nE '^\| *E[0-9]+ *\|[^|]*\| *\| *$' "$doc")"
  if [[ -z "$unanswered" ]]; then
    pass "every edge case has an expected behaviour or N/A"
  else
    fail "every edge case has an expected behaviour or N/A"
    detail "$(echo "$unanswered" | head -8)"
  fi

  if [[ -n "$(design_ids | grep -E '^R[0-9]+$')" ]]; then
    pass "design.md defines requirement IDs"
  else
    fail "design.md defines requirement IDs (R1, R2, ...)"
  fi
fi

# 2. Test plan traces every design ID and names tests that exist.
if [[ ! -f "$plan" ]]; then
  skip "test-plan.md (stage 2 not started)"
elif [[ ! -f "$design" ]]; then
  skip "traceability (no design.md)"
else
  untraced=()
  while read -r id; do
    [[ -z "$id" ]] && continue
    grep -qE "\b${id}\b" "$plan" || untraced+=("$id")
  done < <(design_ids)
  if [[ ${#untraced[@]} -eq 0 ]]; then
    pass "test-plan.md traces every requirement and applicable edge case"
  else
    fail "test-plan.md traces every requirement and applicable edge case"
    detail "not in test-plan.md: ${untraced[*]}"
  fi

  missing_tests=()
  while read -r ref; do
    class="${ref%%#*}"
    method="${ref##*#}"
    file="$(find "$repo_root" -path "$repo_root/.claude" -prune -o -path '*/src/test/*' -name "${class}.java" -print 2>/dev/null | head -1)"
    if [[ -z "$file" ]] || ! grep -qE "void ${method} *\(" "$file"; then
      missing_tests+=("$ref")
    fi
  done < <(grep -oE '`[A-Z][A-Za-z0-9_]*#[a-z][A-Za-z0-9_]*`' "$plan" | tr -d '`' | sort -u)
  if grep -qE '\| *Planned *\|$' "$plan"; then
    skip "tests named in test-plan.md exist (some still Planned)"
  elif [[ ${#missing_tests[@]} -eq 0 ]]; then
    pass "tests named in test-plan.md exist"
  else
    fail "tests named in test-plan.md exist"
    detail "${missing_tests[@]/#/missing: }"
  fi
fi

# User check closed.
user_check="$folder/user-check.md"
if [[ ! -f "$user_check" ]]; then
  skip "user-check.md (run /fusion-user-check)"
elif grep -qE '^User blocking: *0 *$' "$user_check"; then
  pass "user-check.md reports User blocking: 0"
else
  fail "user-check.md reports User blocking: 0"
  detail "$(grep -E '^User blocking' "$user_check" || echo 'no "User blocking:" line')"
fi

# 3. AI review closed.
if [[ ! -f "$review" ]]; then
  skip "ai-review.md (stage 6 not done)"
elif grep -qE '^Open P0/P1: *0 *$' "$review"; then
  pass "ai-review.md reports Open P0/P1: 0"
else
  fail "ai-review.md reports Open P0/P1: 0"
  detail "$(grep -E '^Open P0/P1' "$review" || echo 'no "Open P0/P1:" line')"
fi

# 4. Diff checks against a base ref.
if [[ -z "$base" ]]; then
  skip "diff checks (pass --base <ref>, for example origin/staging)"
else
  cd "$repo_root" || exit 2
  if ! merge_base="$(git merge-base "$base" HEAD 2>/dev/null)"; then
    fail "base ref $base resolves"
  else
    # Changed tracked files and new files not yet added to git.
    untracked="$(git ls-files --others --exclude-standard)"
    stubs="$({
      git diff "$merge_base" -- '*.java' | grep -E '^\+.*UnsupportedOperationException\("not implemented"\)'
      echo "$untracked" | grep -E '\.java$' | while read -r f; do
        grep -HnE 'UnsupportedOperationException\("not implemented"\)' "$f"
      done
    })"
    if [[ -z "$stubs" ]]; then
      pass "no stage 2 stubs left in the diff"
    else
      fail "no stage 2 stubs left in the diff"
      detail "$(echo "$stubs" | head -5)"
    fi

    # Design docs and AI working files are internal (Confluence) and never committed.
    committed="$(git diff --name-only "$merge_base" -- dev/design-docs | grep -v '^dev/design-docs/README.md$')"
    if [[ -z "$committed" ]]; then
      pass "no design-doc working files in the diff"
    else
      fail "no design-doc working files in the diff (they belong on Confluence / stay local)"
      detail $committed
    fi

    unmarked=()
    while read -r file; do
      [[ -f "$file" ]] || continue
      head -40 "$file" | grep -qE 'Licensed to the Apache Software Foundation|Apache License|SPDX-License-Identifier' || continue
      head -40 "$file" | grep -qE 'Modified by Datazip Inc\. in [0-9]{4}' || unmarked+=("$file")
    done < <(git diff --name-only "$merge_base"; echo "$untracked")
    if [[ ${#unmarked[@]} -eq 0 ]]; then
      pass "modified Apache files carry the Datazip modification line"
    else
      fail "modified Apache files carry the Datazip modification line"
      detail "${unmarked[@]}"
    fi
  fi
fi

echo
if [[ $failures -eq 0 ]]; then
  echo "SOP checks passed."
else
  echo "SOP checks failed: $failures."
  exit 1
fi
