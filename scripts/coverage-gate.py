#!/usr/bin/env python3
"""Branch-coverage gate for godwit-core and godwit-test.

Reads the JaCoCo XML report of each module (<module>/build/reports/jacoco/test/jacocoTestReport.xml) and fails on any
uncovered branch in main code that is not listed, with a reason, in coverage-exceptions.txt.

Exception file format, one entry per line; blank lines and lines starting with '#' are ignored:

    <module> <package path>/<SourceFile.kt>:<line> <reason>

for example:

    godwit-core godwit/core/internal/Heartbeat.kt:57 the interrupt arrives only while the JVM is shutting down

An entry names the source line that holds the uncovered branch. An entry without a reason, with an unknown module, a
duplicate, or one that matches no uncovered branch fails the gate, so the list never outlives the branches it excuses.

Output: one line per class that has branches, then one summary line. Exit status 0 when the gate holds, 1 otherwise.

Usage: scripts/coverage-gate.py [repo-dir]. repo-dir defaults to the repository that holds this script.
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

MODULES = ("godwit-core", "godwit-test")
REPORT = "build/reports/jacoco/test/jacocoTestReport.xml"
EXCEPTIONS = "coverage-exceptions.txt"


def parse_exceptions(path):
    """Returns (entries, problems): entries maps (module, location) to its reason."""
    entries, problems = {}, []
    if not path.exists():
        return entries, [f"{path.name}: file not found"]
    for number, raw in enumerate(path.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        where = f"{path.name}:{number}"
        parts = line.split(None, 2)
        if len(parts) < 3:
            problems.append(f"{where}: expected '<module> <path>:<line> <reason>', found '{line}'")
            continue
        module, location, reason = parts
        if module not in MODULES:
            problems.append(f"{where}: unknown module '{module}'")
        elif ":" not in location or not location.rsplit(":", 1)[1].isdigit():
            problems.append(f"{where}: location '{location}' is not '<path>:<line>'")
        elif (module, location) in entries:
            problems.append(f"{where}: duplicate entry for {module} {location}")
        else:
            entries[(module, location)] = reason
    return entries, problems


def read_report(path):
    """Returns (classes, uncovered): classes lists (name, covered, total) for every class with branches, uncovered maps
    'path:line' to the number of missed branches on that line."""
    root = ET.parse(path).getroot()
    classes, uncovered = [], {}
    for package in root.iter("package"):
        package_name = package.get("name")
        for cls in package.iter("class"):
            counter = next((c for c in cls.findall("counter") if c.get("type") == "BRANCH"), None)
            if counter is not None:
                missed, covered = int(counter.get("missed")), int(counter.get("covered"))
                classes.append((cls.get("name"), covered, missed + covered))
        for source in package.iter("sourcefile"):
            for line in source.findall("line"):
                missed = int(line.get("mb"))
                if missed > 0:
                    uncovered[f"{package_name}/{source.get('name')}:{line.get('nr')}"] = missed
    return classes, uncovered


def main(argv):
    repo = pathlib.Path(argv[1]).resolve() if len(argv) > 1 else pathlib.Path(__file__).resolve().parent.parent
    entries, problems = parse_exceptions(repo / EXCEPTIONS)
    covered_total = branch_total = 0
    matched = set()
    class_lines = []
    for module in MODULES:
        report = repo / module / REPORT
        if not report.exists():
            problems.append(f"{module}: no JaCoCo report at {module}/{REPORT}; run ./gradlew test first")
            continue
        classes, uncovered = read_report(report)
        for name, covered, total in classes:
            class_lines.append(f"{module} {name} branches={covered}/{total}")
            covered_total += covered
            branch_total += total
        for location, missed in sorted(uncovered.items()):
            key = (module, location)
            if key in entries:
                matched.add(key)
            else:
                problems.append(f"{module} {location}: {missed} uncovered branch(es) and no entry in {EXCEPTIONS}")
    for module, location in sorted(set(entries) - matched):
        problems.append(f"{EXCEPTIONS}: stale entry '{module} {location}' matches no uncovered branch")

    for line in class_lines:
        print(line)
    for problem in problems:
        print(f"coverage-gate: {problem}")
    status = "FAIL" if problems else "PASS"
    print(
        f"coverage-gate: {status} branches={covered_total}/{branch_total} "
        f"exceptions={len(matched)} problems={len(problems)}"
    )
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
