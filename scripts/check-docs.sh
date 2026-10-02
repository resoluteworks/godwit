#!/usr/bin/env bash
# Runs every documentation check, one after another, and stops at the first one that exits non-zero:
#   1. docs-snippets compile      (./gradlew --offline compileKotlin in docs-snippets/)
#   2. neg-check.sh               (every neg/ snippet fails to compile with its expected message)
#   3. check-snippets.sh          (every kotlin block in README.md and docs/**/*.md is in a compiled or neg/ file)
#   4. check-links.sh             (every relative link and anchor resolves)
#   5. check-content.sh           (content rules)
# Usage: scripts/check-docs.sh. Works from any directory; paths resolve relative to this script.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

step() {
  echo
  echo "==> $1"
}

step "docs-snippets compile"
(cd "$here/../docs-snippets" && ./gradlew --offline compileKotlin)

step "neg-check"
"$here/neg-check.sh"

step "check-snippets"
"$here/check-snippets.sh"

step "check-links"
"$here/check-links.sh"

step "check-content"
"$here/check-content.sh"

echo
echo "check-docs: all checks passed"
