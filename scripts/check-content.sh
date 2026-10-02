#!/usr/bin/env bash
# Content rules for every .kt, .kts, .md and .sh file under a directory (default: the repository that holds this script,
# so README.md, docs/**, docs-snippets/** and scripts/**): no em dash, en dash only inside a numeric range, no history
# narrative, no banned phrases, and (in README.md and docs/**, outside docs/development) none of
# the docs build's own packages or comments.
# Usage: scripts/check-content.sh [dir]. Exits 0 when every rule holds.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dir="$(cd "${1:-$here/..}" && pwd)"
python3 - "$dir" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
skip_dirs = {"build", ".gradle", ".kotlin", "gradle", ".git"}
suffixes = {".kt", ".kts", ".md", ".sh"}
# The dashes are written as escapes and each phrase ends in a one-character class, so this file's own source matches
# none of the rules and the script checks itself like every other file.
rules = {
    "em dash": re.compile("\u2014"),
    "en dash outside a numeric range": re.compile(r"(?<![0-9])\u2013|\u2013(?![0-9])"),
    "history narrative": re.compile(
        r"previousl[y]|used t[o]|was change[d]|moved fro[m]|now use[s]|replaces the ol[d]|renamed fro[m]", re.I),
    "banned phrases": re.compile(
        r"here.s the kicke[r]|here.s the thin[g]|plot twis[t]|let me break this dow[n]|the bottom lin[e]"
        r"|make no mistak[e]", re.I),
}
# Rules for the published docs only (README.md and docs/**): the docs build's own scaffolding must not leak into them.
docs_only_rules = {
    "docs-build scaffolding": re.compile(r"com\.example\.shop\.docs|sits in main here|compile-checked\.$"),
}
files = [p for p in root.rglob("*") if p.is_file() and p.suffix in suffixes
         and not (set(p.relative_to(root).parts) & skip_dirs)]
failures = 0
# docs/development describes the docs build itself, so it may name the build's packages.
published = [p for p in files if p.relative_to(root).parts[:2] != ("docs", "development")
             and (p.relative_to(root).parts[0] == "docs" or p.relative_to(root).parts == ("README.md",))]
for label, rx, scope in [(l, r, files) for l, r in rules.items()] + [(l, r, published) for l, r in docs_only_rules.items()]:
    hits = [f"{p.relative_to(root)}:{n}: {line.strip()}" for p in sorted(scope)
            for n, line in enumerate(p.read_text(errors="replace").splitlines(), 1) if rx.search(line)]
    print(("FAIL " if hits else "PASS ") + label + (":" if hits else ""))
    for h in hits: print("  " + h)
    failures += bool(hits)
print(f"checked {len(files)} files")
sys.exit(1 if failures else 0)
PY
