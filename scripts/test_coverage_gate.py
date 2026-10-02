"""Tests for coverage-gate.py: every branch of the gate, over JaCoCo reports written to a temporary repository.

Usage: python3 -m unittest discover -s scripts -p 'test_*.py'
"""
import contextlib
import importlib.util
import io
import pathlib
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("coverage_gate", HERE / "coverage-gate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

HEADER = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' \
         '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">\n'


def report(classes=(), lines=()):
    """A JaCoCo report with one package. classes: (name, missed, covered) branch counters, with None for a class that
    has no branches and so no counter; lines: (source, nr, mb, cb)."""
    body = ""
    for name, missed, covered in classes:
        body += f'<class name="godwit/core/{name}" sourcefilename="{name}.kt">'
        body += '<method name="m" desc="()V" line="1"><counter type="BRANCH" missed="9" covered="9"/></method>'
        if missed is not None:
            body += f'<counter type="BRANCH" missed="{missed}" covered="{covered}"/>'
        body += "</class>"
    for source, nr, mb, cb in lines:
        body += f'<sourcefile name="{source}"><line nr="{nr}" mi="0" ci="1" mb="{mb}" cb="{cb}"/></sourcefile>'
    package = f'<package name="godwit/core">{body}</package>' if body else ""
    return HEADER + f'<report name="m"><sessioninfo id="s" start="1" dump="2"/>{package}</report>'


class CoverageGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = pathlib.Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        self.write_exceptions("# no entries\n\n")

    def write_exceptions(self, text):
        (self.repo / "coverage-exceptions.txt").write_text(text)

    def write_report(self, module, xml):
        path = self.repo / module / gate.REPORT
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(xml)

    def run_gate(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = gate.main(["coverage-gate.py", str(self.repo)])
        return code, out.getvalue().splitlines()

    def test_empty_modules_pass_with_zero_branches(self):
        self.write_report("godwit-core", report())
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(0, code)
        self.assertEqual(["coverage-gate: PASS branches=0/0 exceptions=0 problems=0"], lines)

    def test_fully_covered_branches_pass_and_each_class_is_listed(self):
        self.write_report("godwit-core", report(classes=[("Planner", 0, 8)], lines=[("Planner.kt", 4, 0, 2)]))
        self.write_report("godwit-test", report(classes=[("Kit", 0, 2)]))
        code, lines = self.run_gate()
        self.assertEqual(0, code)
        self.assertEqual(
            [
                "godwit-core godwit/core/Planner branches=8/8",
                "godwit-test godwit/core/Kit branches=2/2",
                "coverage-gate: PASS branches=10/10 exceptions=0 problems=0",
            ],
            lines,
        )

    def test_a_class_without_branches_is_not_listed(self):
        self.write_report("godwit-core", report(classes=[("Plain", None, None), ("Planner", 0, 2)]))
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(0, code)
        self.assertEqual(
            [
                "godwit-core godwit/core/Planner branches=2/2",
                "coverage-gate: PASS branches=2/2 exceptions=0 problems=0",
            ],
            lines,
        )

    def test_an_uncovered_branch_without_an_entry_fails(self):
        self.write_report("godwit-core", report(classes=[("Planner", 1, 1)], lines=[("Planner.kt", 4, 1, 1)]))
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("godwit-core godwit/core/Planner branches=1/2", lines)
        self.assertIn(
            "coverage-gate: godwit-core godwit/core/Planner.kt:4: 1 uncovered branch(es) and no entry in "
            "coverage-exceptions.txt",
            lines,
        )
        self.assertEqual("coverage-gate: FAIL branches=1/2 exceptions=0 problems=1", lines[-1])

    def test_an_uncovered_branch_in_the_second_module_fails(self):
        self.write_report("godwit-core", report())
        self.write_report("godwit-test", report(lines=[("Kit.kt", 9, 2, 0)]))
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn(
            "coverage-gate: godwit-test godwit/core/Kit.kt:9: 2 uncovered branch(es) and no entry in "
            "coverage-exceptions.txt",
            lines,
        )

    def test_a_listed_uncovered_branch_passes(self):
        self.write_exceptions(
            "# a comment\n"
            "godwit-core godwit/core/Planner.kt:4 the branch needs a JVM that is shutting down\n"
        )
        self.write_report("godwit-core", report(classes=[("Planner", 1, 1)], lines=[("Planner.kt", 4, 1, 1)]))
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(0, code)
        self.assertEqual("coverage-gate: PASS branches=1/2 exceptions=1 problems=0", lines[-1])

    def test_an_entry_that_matches_no_uncovered_branch_is_stale_and_fails(self):
        self.write_exceptions("godwit-core godwit/core/Planner.kt:4 the branch needs a JVM that is shutting down\n")
        self.write_report("godwit-core", report(classes=[("Planner", 0, 2)], lines=[("Planner.kt", 4, 0, 2)]))
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn(
            "coverage-gate: coverage-exceptions.txt: stale entry 'godwit-core godwit/core/Planner.kt:4' matches no "
            "uncovered branch",
            lines,
        )

    def test_an_entry_without_a_reason_fails(self):
        self.write_exceptions("godwit-core godwit/core/Planner.kt:4\n")
        self.write_report("godwit-core", report(lines=[("Planner.kt", 4, 1, 1)]))
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn(
            "coverage-gate: coverage-exceptions.txt:1: expected '<module> <path>:<line> <reason>', found "
            "'godwit-core godwit/core/Planner.kt:4'",
            lines,
        )

    def test_an_entry_for_an_unknown_module_fails(self):
        self.write_exceptions("godwit-other godwit/core/Planner.kt:4 a reason\n")
        self.write_report("godwit-core", report())
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("coverage-gate: coverage-exceptions.txt:1: unknown module 'godwit-other'", lines)

    def test_an_entry_with_a_malformed_location_fails(self):
        self.write_exceptions("godwit-core Planner.kt a reason\ngodwit-core Planner.kt:x a reason\n")
        self.write_report("godwit-core", report())
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("coverage-gate: coverage-exceptions.txt:1: location 'Planner.kt' is not '<path>:<line>'", lines)
        self.assertIn("coverage-gate: coverage-exceptions.txt:2: location 'Planner.kt:x' is not '<path>:<line>'", lines)

    def test_a_duplicate_entry_fails(self):
        self.write_exceptions(
            "godwit-core godwit/core/Planner.kt:4 a reason\n"
            "godwit-core godwit/core/Planner.kt:4 another reason\n"
        )
        self.write_report("godwit-core", report(lines=[("Planner.kt", 4, 1, 1)]))
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn(
            "coverage-gate: coverage-exceptions.txt:2: duplicate entry for godwit-core godwit/core/Planner.kt:4", lines
        )

    def test_a_missing_exceptions_file_fails(self):
        (self.repo / "coverage-exceptions.txt").unlink()
        self.write_report("godwit-core", report())
        self.write_report("godwit-test", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("coverage-gate: coverage-exceptions.txt: file not found", lines)

    def test_a_missing_report_fails(self):
        self.write_report("godwit-core", report())
        code, lines = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn(
            "coverage-gate: godwit-test: no JaCoCo report at godwit-test/build/reports/jacoco/test/"
            "jacocoTestReport.xml; run ./gradlew test first",
            lines,
        )

    def test_the_repository_defaults_to_the_one_that_holds_the_script(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = gate.main(["coverage-gate.py"])
        self.assertIn(code, (0, 1))
        self.assertTrue(out.getvalue().splitlines()[-1].startswith("coverage-gate: "))


if __name__ == "__main__":
    unittest.main()
