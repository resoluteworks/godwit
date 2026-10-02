#!/usr/bin/env bash
# Proves every ```kotlin block in a repo's README.md and docs/**/*.md is compile-checked.
#
# Each block is compared line by line after whitespace normalisation (leading and trailing whitespace stripped, inner
# runs of whitespace collapsed to one space, blank lines dropped) and must appear as a contiguous run of lines in one
# .kt file:
#   - under docs-snippets/src/main/kotlin, which `./gradlew --offline compileKotlin` compiles in docs-snippets/, or
#   - under docs-snippets/neg/, when the block's previous non-blank line is exactly "This does not compile:";
#     scripts/neg-check.sh proves each neg/ snippet fails to compile.
# A block that also matches byte for byte (one uniform indent) is counted as verbatim; the rest are reported as
# whitespace-only matches so they can be tidied.
#
# Usage: scripts/check-snippets.sh [repo-dir]. repo-dir defaults to the repository that holds this script.
# Exits 0 when every block is found.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "${1:-$here/..}" && pwd)"
snippets="$here/../docs-snippets"
python3 - "$repo" "$snippets/src/main/kotlin" "$snippets/neg" <<'PY'
import pathlib, re, sys

repo, sources, negdir = (pathlib.Path(a) for a in sys.argv[1:4])
MARKER = "This does not compile:"


def norm(line):
    return re.sub(r"\s+", " ", line.strip())


def load(paths):
    files = {}
    for p in paths:
        raw = [l.rstrip() for l in p.read_text().splitlines()]
        files[p] = (raw, [n for n in (norm(l) for l in raw) if n])
    return files


compiled = load(sorted(sources.rglob("*.kt")))
negative = load(sorted(negdir.glob("*.kt")))


def contains(haystack, needle):
    n = len(needle)
    return any(haystack[i:i + n] == needle for i in range(len(haystack) - n + 1))


def verbatim(block, raw):
    first = next(i for i, l in enumerate(block) if l.strip())
    for start in range(len(raw) - len(block) + 1):
        anchor = raw[start + first]
        if not anchor.endswith(block[first]):
            continue
        indent = anchor[: len(anchor) - len(block[first])]
        if indent.strip():
            continue
        if all((b == "" and s == "") or s == indent + b for b, s in zip(block, raw[start:start + len(block)])):
            return True
    return False


docs = [repo / "README.md"] + (sorted((repo / "docs").rglob("*.md")) if (repo / "docs").exists() else [])
total = exact = 0
missing, loose = [], []
per_doc = {}
for doc in (d for d in docs if d.exists()):
    text = doc.read_text().splitlines()
    rel = str(doc.relative_to(repo))
    i = 0
    while i < len(text):
        if text[i].strip() == "```kotlin":
            j = i + 1
            while j < len(text) and text[j].strip() != "```":
                j += 1
            block = [l.rstrip() for l in text[i + 1:j]]
            while block and not block[-1]:
                block.pop()
            before = next((t.strip() for t in reversed(text[:i]) if t.strip()), "")
            pool, where = (negative, "neg/") if before == MARKER else (compiled, "src/main/kotlin")
            needle = [n for n in (norm(l) for l in block) if n]
            total += 1
            per_doc[rel] = per_doc.get(rel, 0) + 1
            hit = next((p for p, (_, normed) in pool.items() if needle and contains(normed, needle)), None)
            if hit is None:
                missing.append(f"{rel}:{i + 1} (looked in {where})")
            elif verbatim(block, pool[hit][0]):
                exact += 1
            else:
                loose.append(f"{rel}:{i + 1} -> {hit.relative_to(negdir.parent)}")
            i = j
        i += 1

for doc, count in sorted(per_doc.items()):
    print(f"  {doc}: {count} kotlin blocks")
for m in loose:
    print(f"WHITESPACE-ONLY MATCH: {m}")
for m in missing:
    print(f"NOT FOUND: kotlin block at {m}")
print(f"check-snippets: {total - len(missing)}/{total} kotlin blocks found after whitespace normalisation "
      f"({exact} byte for byte, {total - len(missing) - exact} whitespace-only), {len(missing)} missing")
sys.exit(1 if missing else 0)
PY
