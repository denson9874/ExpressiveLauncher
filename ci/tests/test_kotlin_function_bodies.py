"""Kotlin functions keep block bodies: `fun f() = { ... }` returns a lambda and never runs it.

5.0.6 shipped blank Overview cards because TaskContainer.bind, destroy, refreshOverlay and setState
were written that way after a merge stripped their traceSection wrappers. They compiled, and every
caller dropped the lambda without a word.

A function that means to return a lambda says so with a return type, `fun f(): () -> Unit = {`,
which this check leaves alone.
"""

import os
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).parents[2]
SKIPPED_DIRS = {".git", ".gradle", "build", "node_modules"}
# Cheap filter: only files with `) = {` somewhere need the full scan.
CANDIDATE = re.compile(r"\)\s*=\s*\{")
FUN = re.compile(r"\bfun\b")
CHAR_LITERAL = re.compile(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'")


def kotlin_sources():
    for directory, subdirs, files in os.walk(ROOT):
        subdirs[:] = [name for name in subdirs if name not in SKIPPED_DIRS]
        for name in files:
            if name.endswith(".kt"):
                yield Path(directory, name)


def blank(text):
    return re.sub(r"[^\n]", " ", text)


def strip_comments_and_strings(text):
    """Blank out comments and string and char literals, keeping every newline in place."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
        elif text.startswith("/*", i):
            depth, end = 0, i
            while end < n:
                if text.startswith("/*", end):
                    depth, end = depth + 1, end + 2
                elif text.startswith("*/", end):
                    depth, end = depth - 1, end + 2
                    if depth == 0:
                        break
                else:
                    end += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
        elif text[i] == '"':
            end = i + 1
            while end < n and text[end] not in '"\n':
                end += 2 if text[end] == "\\" else 1
            end = min(end + 1, n)
        elif text[i] == "'" and (literal := CHAR_LITERAL.match(text, i)):
            end = literal.end()
        else:
            out.append(text[i])
            i += 1
            continue
        out.append(blank(text[i:end]))
        i = end
    return "".join(out)


def skip_space(code, i):
    while i < len(code) and code[i].isspace():
        i += 1
    return i


def lambda_bodies(text):
    """Line numbers of functions declared `fun name(...) = {` with no return type."""
    if not CANDIDATE.search(text):
        return []
    code = strip_comments_and_strings(text)
    found = []
    for match in FUN.finditer(code):
        # Type parameters, receiver and name, up to the parameter list.
        i, angle = match.end(), 0
        while i < len(code) and not (angle == 0 and code[i] in "({=;"):
            angle += {"<": 1, ">": -1}.get(code[i], 0)
            i += 1
        if i == len(code) or code[i] != "(":
            continue
        depth = 0
        while i < len(code):
            depth += {"(": 1, ")": -1}.get(code[i], 0)
            if depth == 0:
                break
            i += 1
        i = skip_space(code, i + 1)
        if code.startswith("=", i) and not code.startswith("==", i):
            if code.startswith("{", skip_space(code, i + 1)):
                found.append(code.count("\n", 0, match.start()) + 1)
    return found


class KotlinFunctionBodiesTest(unittest.TestCase):
    def test_finds_lambda_bodies(self):
        cases = [
            "    fun bind() = {\n        run()\n    }\n",
            "    private fun setState(\n        a: Int,\n    ) = {\n",
            "    fun destroy() = { overlay.reset() }\n",
            "    fun bind() = { // a note\n",
            "    fun bind() =\n        {\n",
            '    @Suppress("X") fun bind() = {\n',
            "    fun f(cb: () -> Unit = {}) = {\n",
            "    fun <T : Comparable<T>> T.f(x: T) = {\n",
        ]
        for case in cases:
            with self.subTest(case=case):
                self.assertEqual(lambda_bodies(case), [1])

    def test_leaves_other_functions_alone(self):
        cases = [
            "    fun bind() {\n        run()\n    }\n",
            "    fun size() = items.size\n",
            "    fun action(): () -> Unit = {\n",
            "    fun handler(): ((Int) -> Unit) = {\n",
            "    fun show(onDone: () -> Unit = {}) {\n",
            "    fun Foo(\n        onDone: (() -> Unit) = {\n        },\n    ) {\n",
            "    fun size() = items.size\n    override val t: () -> Unit\n        get() = {\n",
            "    abstract fun reset()\n    val t: () -> Unit\n        get() = {\n",
            "    fun same(a: Int) = a == { 1 }()\n",
            '    val fixture = """\n        fun bind() = {\n    """\n',
            '    val fixture = "fun bind() = {"\n',
            "    // fun bind() = {\n",
            "    /* fun bind() = {\n    */\n",
            "    fun interface Callback {\n        fun run()\n    }\n",
        ]
        for case in cases:
            with self.subTest(case=case):
                self.assertEqual(lambda_bodies(case), [])

    def test_reports_the_line_of_fun(self):
        self.assertEqual(lambda_bodies("class A {\n\n    fun bind(\n        a: Int,\n    ) = {\n"), [3])

    def test_no_function_returns_its_body_as_a_lambda(self):
        found = []
        for path in kotlin_sources():
            text = path.read_text(encoding="utf-8", errors="replace")
            found += [f"{path.relative_to(ROOT)}:{line}" for line in lambda_bodies(text)]
        self.assertEqual(found, [], "use a block body, `fun f() {`, or the body never runs")


if __name__ == "__main__":
    unittest.main()
