"""Kotlin functions keep block bodies: `fun f() = { ... }` returns a lambda and never runs it.

5.0.6 shipped blank Overview cards because TaskContainer.bind, destroy, refreshOverlay and setState
were written that way after a merge stripped their traceSection wrappers. They compiled, and every
caller dropped the lambda without a word.

A function that means to return a lambda says so with a return type, `fun f(): () -> Unit = {`,
which this check leaves alone, as it does a body that is called or chained on the spot,
`fun f() = { ... }()`. String templates aren't parsed, so a raw string nested in one can still
confuse it.
"""

import os
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).parents[2]
SKIPPED_DIRS = {".git", ".gradle", "build", "node_modules"}
# Cheap filter: only files with `) = {` (comments allowed around the `=`) or a `where` clause
# need the full scan.
GAP = r"(?:\s|//[^\n]*|/\*.*?\*/)*"
CANDIDATE = re.compile(rf"\){GAP}={GAP}\{{|\bwhere\b", re.S)
FUN = re.compile(r"\bfun\b")
WHERE = re.compile(r"where\b")
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
            # `""""q""""`: the raw string ends at the last three quotes of the run.
            while end < n and text[end] == '"':
                end += 1
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


def closing(code, i, pair="()"):
    """Index of the bracket that closes the one at i, or len(code)."""
    depth = 0
    while i < len(code):
        depth += {pair[0]: 1, pair[1]: -1}.get(code[i], 0)
        if depth == 0:
            return i
        i += 1
    return i


def parameter_list(code, i):
    """Index of the `(` that opens a function's parameters, walking from just after `fun`."""
    angle = 0
    while i < len(code):
        if code.startswith("->", i):
            i += 2
            continue
        if angle == 0 and code[i] in "{=;":
            return -1
        if angle == 0 and code[i] == "(":
            after = skip_space(code, closing(code, i) + 1)
            if not code.startswith((".", "?."), after):
                return i
            # A function-type receiver, `fun (() -> Unit).twice()`: the name comes next.
            i = after + (1 if code[after] == "." else 2)
            continue
        angle += {"<": 1, ">": -1}.get(code[i], 0)
        if angle < 0:
            return -1
        i += 1
    return -1


def skip_where(code, i):
    """Index just past a `where T : A, U : B` clause starting at i."""
    i += len("where")
    while True:
        angle, depth = 0, 0
        while i < len(code):
            if code.startswith("->", i):
                i += 2
                continue
            c = code[i]
            if angle == depth == 0 and (c in ",{=;" or c == "\n"):
                break
            angle += {"<": 1, ">": -1}.get(c, 0)
            depth += {"(": 1, ")": -1}.get(c, 0)
            i += 1
        if not code.startswith(",", i):
            return i
        i = skip_space(code, i + 1)


def called_on_the_spot(code, body):
    """True for `= { ... }()`, `= { ... }.invoke()` and other bodies used right away."""
    end = closing(code, body, "{}")
    if end == len(code):
        return False
    after = end + 1
    while after < len(code) and code[after] in " \t":
        after += 1
    return code.startswith("(", after) or code.startswith((".", "?."), skip_space(code, after))


def lambda_bodies(text):
    """Line numbers of functions declared `fun name(...) = {` with no return type."""
    if not CANDIDATE.search(text):
        return []
    code = strip_comments_and_strings(text)
    found = []
    for match in FUN.finditer(code):
        i = parameter_list(code, match.end())
        if i < 0:
            continue
        i = skip_space(code, closing(code, i) + 1)
        if WHERE.match(code, i):
            i = skip_space(code, skip_where(code, i))
        if code.startswith("=", i) and not code.startswith("==", i):
            body = skip_space(code, i + 1)
            if code.startswith("{", body) and not called_on_the_spot(code, body):
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
            "    fun <T> List<(T) -> Unit>.callAll(t: T) = {\n",
            "    fun <T : (Int) -> Unit> T.call() = {\n",
            "    fun (() -> Unit).twice() = {\n",
            "    fun (() -> Unit)?.twice() = {\n",
            "    fun <T> f(x: T) where T : Any = {\n",
            "    fun <T, U> f(x: T, y: U)\n        where T : Comparable<T>,\n              U : (Int) -> Unit = {\n",
            "    fun bind() = // x\n        {\n",
            "    fun bind() = /* x */ {\n",
            '    val s = """"q""""; fun bind() = {\n',
            "    fun bind() = {\n        run()\n    }\n    (a)\n",
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
            "    fun <T> List<(T) -> Unit>.dispatch(value: T) = forEach { it(value) }\n"
            "    val ordered get() = a < b\n    val t: () -> Unit\n        get() = {\n",
            "    fun interface Handler : Consumer<(Int) -> Unit> { fun handle() }\n"
            "    val t: () -> Unit\n        get() = {\n",
            "    fun (() -> Unit).twice() = 1\n",
            "    fun <T> f(x: T): () -> Unit where T : Any = {\n",
            "    abstract fun <T> f(x: T) where T : Any\n    val t: () -> Unit = {\n",
            "    fun f() = { compute() }()\n",
            "    fun f() = {\n        compute()\n    }.invoke()\n",
            "    fun r() = { work() }.let { Runnable(it) }\n",
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
