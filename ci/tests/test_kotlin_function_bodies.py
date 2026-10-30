"""Kotlin functions keep block bodies: `fun f() = { ... }` returns a lambda and never runs it.

5.0.6 shipped blank Overview cards because TaskContainer.bind, destroy, refreshOverlay and setState
were written that way after a merge stripped their traceSection wrappers. They compiled, and every
caller dropped the lambda without a word.
"""

import os
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).parents[2]
SKIPPED_DIRS = {".git", ".gradle", "build", "node_modules"}
# A `fun` header whose parameter list closes on `= {` at the end of a line. The header may span
# lines but holds no braces, so default lambda arguments and block bodies never match.
LAMBDA_BODY = re.compile(r"^[ \t]*(?:[\w@]+[ \t]+)*fun\b[^{}]*?\)[ \t]*=[ \t]*\{[ \t]*$", re.M)


def kotlin_sources():
    for directory, subdirs, files in os.walk(ROOT):
        subdirs[:] = [name for name in subdirs if name not in SKIPPED_DIRS]
        for name in files:
            if name.endswith(".kt"):
                yield Path(directory, name)


def lambda_bodies(text):
    return [text.count("\n", 0, match.start()) + 1 for match in LAMBDA_BODY.finditer(text)]


class KotlinFunctionBodiesTest(unittest.TestCase):
    def test_pattern_finds_lambda_bodies_only(self):
        self.assertEqual(lambda_bodies("    fun bind() = {\n        run()\n    }\n"), [1])
        self.assertEqual(lambda_bodies("    private fun setState(\n        a: Int,\n    ) = {\n"), [1])
        self.assertEqual(lambda_bodies("    fun bind() {\n        run()\n    }\n"), [])
        self.assertEqual(lambda_bodies("    fun size() = items.size\n"), [])
        self.assertEqual(lambda_bodies("    fun action(): () -> Unit = {\n"), [])
        self.assertEqual(lambda_bodies("    fun show(onDone: () -> Unit = {}) {\n"), [])

    def test_no_function_returns_its_body_as_a_lambda(self):
        found = []
        for path in kotlin_sources():
            text = path.read_text(encoding="utf-8", errors="replace")
            found += [f"{path.relative_to(ROOT)}:{line}" for line in lambda_bodies(text)]
        self.assertEqual(found, [], "use a block body, `fun f() {`, or the body never runs")


if __name__ == "__main__":
    unittest.main()
