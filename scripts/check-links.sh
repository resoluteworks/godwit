#!/usr/bin/env bash
# Checks every relative Markdown link in a repo's README.md and docs/**/*.md: the target file must exist and, when the
# link has a #fragment, the target Markdown file must have a heading (GitHub slug rules, duplicates suffixed -1, -2,
# ...) or an explicit <a id|name="..."> with that anchor. Links inside fenced code blocks and inline code are ignored;
# absolute URLs (http:, https:, mailto:) are not checked.
# Usage: scripts/check-links.sh [repo-dir]. repo-dir defaults to the repository that holds this script.
# Exits 0 when every link resolves.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="${1:-$here/..}"
python3 - "$repo" <<'PY'
import pathlib, re, sys

repo = pathlib.Path(sys.argv[1]).resolve()
docs = [repo / "README.md"] + (sorted((repo / "docs").rglob("*.md")) if (repo / "docs").exists() else [])
docs = [d for d in docs if d.exists()]

FENCE = re.compile(r"^\s*(```|~~~)")
INLINE_CODE = re.compile(r"`+[^`]*`+")
LINK = re.compile(r"(?<!\!)\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
REF_DEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+.*)?$")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
EXPLICIT = re.compile(r"<a\s+(?:id|name)=\"([^\"]+)\"", re.I)


def prose_lines(path):
    """Yields (line number, text) outside fenced code blocks."""
    fenced = False
    for n, line in enumerate(path.read_text().splitlines(), 1):
        if FENCE.match(line):
            fenced = not fenced
            continue
        if not fenced:
            yield n, line


def slug(text):
    text = re.sub(r"<[^>]+>", "", text)                       # inline HTML
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)      # links keep their text
    text = text.replace("`", "").strip().lower()
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)    # GitHub keeps letters, digits, _, -, spaces
    return text.replace(" ", "-")


anchors_cache = {}


def anchors(path):
    if path not in anchors_cache:
        seen, result = {}, set()
        for _, line in prose_lines(path):
            m = HEADING.match(line)
            if m:
                base = slug(m.group(2))
                count = seen.get(base, 0)
                result.add(base if count == 0 else f"{base}-{count}")
                seen[base] = count + 1
            result.update(EXPLICIT.findall(line))
        anchors_cache[path] = result
    return anchors_cache[path]


total, broken = 0, []
for doc in docs:
    for n, line in prose_lines(doc):
        stripped = INLINE_CODE.sub("", line)
        targets = [m.group(1) for m in LINK.finditer(stripped)]
        ref = REF_DEF.match(stripped)
        if ref:
            targets.append(ref.group(1))
        for target in targets:
            if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
                continue
            total += 1
            file_part, _, fragment = target.partition("#")
            dest = doc if not file_part else (doc.parent / file_part).resolve()
            where = f"{doc.relative_to(repo)}:{n}: {target}"
            if not dest.exists():
                broken.append(f"{where} (no such file)")
                continue
            try:
                dest.relative_to(repo)
            except ValueError:
                broken.append(f"{where} (outside the repo)")
                continue
            if fragment:
                if dest.suffix != ".md":
                    broken.append(f"{where} (fragment on a non-Markdown file)")
                elif fragment not in anchors(dest):
                    broken.append(f"{where} (no anchor #{fragment} in {dest.relative_to(repo)})")

for b in broken:
    print(f"BROKEN {b}")
print(f"check-links: {total - len(broken)}/{total} relative links resolve across {len(docs)} files")
sys.exit(1 if broken else 0)
PY
