"""Entry scripts stop with a clear message on an old interpreter, and their children reuse the caller's.

macOS's /usr/bin/python3 is 3.9, which cannot run these scripts (PEP 604 unions, hashlib.file_digest).
Each entry script therefore checks the version inline, before any other import: Jenkins and
ci/pipeline.py run some of them as lone copies, so a shared guard module cannot be imported.
"""

import ast
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).parents[2]
INTERPRETER = "/opt/homebrew/bin/python3.14"
OLD_PYTHON = Path("/usr/bin/python3")
# Run directly by the GitHub workflows; it dispatches at module level instead of under __main__.
MODULE_LEVEL_ENTRIES = {ROOT / "ci.py"}
PYTHON_NAME = re.compile(r"python[0-9.]*")
SUBPROCESS_CALLS = {"run", "run_cmd", "call", "check_call", "check_output", "Popen"}


def scripts():
    paths = [*ROOT.glob("scripts/*.py"), *ROOT.glob("ci/*.py"), *ROOT.glob("ci/jenkins/*.py"), *MODULE_LEVEL_ENTRIES]
    return {path: ast.parse(path.read_text(encoding="utf-8"), str(path)) for path in sorted(paths)}


def is_main_block(node):
    return (isinstance(node, ast.If) and isinstance(node.test, ast.Compare)
            and isinstance(node.test.left, ast.Name) and node.test.left.id == "__name__")


def entry_scripts():
    return {path: tree for path, tree in scripts().items()
            if path in MODULE_LEVEL_ENTRIES or any(is_main_block(node) for node in tree.body)}


def guard_problem(tree):
    """Return why the module does not start with the version guard, or None."""
    body = tree.body
    index = 0
    if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) \
            and isinstance(body[0].value.value, str):
        index = 1
    while index < len(body) and isinstance(body[index], ast.ImportFrom) and body[index].module == "__future__":
        index += 1
    if index + 1 >= len(body):
        return "no statements after the docstring"
    first, check = body[index], body[index + 1]
    if not (isinstance(first, ast.Import) and [alias.name for alias in first.names] == ["sys"]):
        return "the first statement after the docstring is not 'import sys'"
    if not (isinstance(check, ast.If) and ast.unparse(check.test) == "sys.version_info < (3, 11)"):
        return "'import sys' is not followed by 'if sys.version_info < (3, 11):'"
    if not any(isinstance(node, ast.Call) and ast.unparse(node.func) == "sys.exit" and INTERPRETER in ast.unparse(node)
               for statement in check.body for node in ast.walk(statement)):
        return f"the version check does not sys.exit with a message naming {INTERPRETER}"
    return None


def old_python():
    if not OLD_PYTHON.is_file():
        return None
    try:
        probe = subprocess.run([str(OLD_PYTHON), "-c", "import sys; print(sys.version_info < (3, 11))"],
                               capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return OLD_PYTHON if probe.returncode == 0 and probe.stdout.strip() == "True" else None


class EntryScriptInterpreterTests(unittest.TestCase):
    def test_every_entry_script_checks_the_version_before_anything_else(self):
        entries = entry_scripts()
        self.assertIn(ROOT / "scripts/release_unified.py", entries)
        self.assertIn(ROOT / "ci/publish_qa.py", entries)
        for path, tree in entries.items():
            with self.subTest(script=str(path.relative_to(ROOT))):
                self.assertIsNone(guard_problem(tree))

    def test_entry_scripts_parse_as_python_3_9_so_the_check_can_run(self):
        # A match statement, for example, is a SyntaxError on 3.9 before any check runs.
        for path in entry_scripts():
            with self.subTest(script=str(path.relative_to(ROOT))):
                ast.parse(path.read_text(encoding="utf-8"), str(path), feature_version=(3, 9))

    def test_no_subprocess_command_starts_with_a_bare_python(self):
        offenders = []
        for path, tree in scripts().items():
            for node in ast.walk(tree):
                if isinstance(node, (ast.List, ast.Tuple)) and node.elts:
                    first = node.elts[0]
                elif isinstance(node, ast.Call) and node.args and \
                        (getattr(node.func, "id", None) or getattr(node.func, "attr", None)) in SUBPROCESS_CALLS:
                    first = node.args[0]
                else:
                    continue
                if isinstance(first, ast.Constant) and isinstance(first.value, str) \
                        and PYTHON_NAME.fullmatch(first.value):
                    offenders.append(f"{path.relative_to(ROOT)}:{node.lineno}")
        self.assertEqual([], offenders, "launch child scripts with sys.executable, not a PATH lookup")

    def test_old_interpreter_prints_the_message_instead_of_a_traceback(self):
        python = old_python()
        if python is None:
            self.skipTest(f"{OLD_PYTHON} is missing or not older than 3.11")
        env = {key: value for key, value in os.environ.items() if not key.startswith("PYTHON")}
        with tempfile.TemporaryDirectory() as cwd:
            for path, tree in entry_scripts().items():
                with self.subTest(script=str(path.relative_to(ROOT))):
                    # Only ever run a script that is known to stop at its first statements.
                    self.assertIsNone(guard_problem(tree))
                    result = subprocess.run([str(python), str(path), "--help"], cwd=cwd, env=env,
                                            capture_output=True, text=True, timeout=60)
                    self.assertEqual(1, result.returncode, result.stderr)
                    self.assertEqual("", result.stdout)
                    self.assertNotIn("Traceback", result.stderr)
                    self.assertIn(INTERPRETER, result.stderr)


if __name__ == "__main__":
    unittest.main()
