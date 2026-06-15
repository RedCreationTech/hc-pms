#!/usr/bin/env python3
"""Check namespace and function line constraints for Clojure/ClojureScript sources."""

import os
import re
import sys
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parent.parent
SRC_DIRS = [ROOT / "src" / "clj", ROOT / "src" / "cljs"]
NS_LIMIT = 500
FN_LIMIT = 40


def tokenize(text):
    """Yield (token, line) pairs, skipping comments and handling strings."""
    line = 1
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        if ch.isspace() or ch == ",":
            i += 1
            continue
        if ch == ";":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == '"':
            start_line = line
            j = i + 1
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    if text[j + 1] == "\n":
                        line += 1
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                if text[j] == "\n":
                    line += 1
                j += 1
            yield (text[i:j], start_line)
            i = j
            continue
        # regex #"..." roughly
        if ch == "#" and i + 1 < n and text[i + 1] == '"':
            start_line = line
            j = i + 2
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    if text[j + 1] == "\n":
                        line += 1
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                if text[j] == "\n":
                    line += 1
                j += 1
            yield (text[i:j], start_line)
            i = j
            continue
        if ch in PAREN_OPEN or ch in PAREN_CLOSE:
            yield (ch, line)
            i += 1
            continue
        # token
        start_line = line
        j = i
        while j < n:
            c = text[j]
            if c.isspace() or c in ",;()[]{}\"":
                break
            if c == "#" and j + 1 < n and text[j + 1] == '"':
                break
            if c == "\n":
                line += 1
            j += 1
        if j > i:
            yield (text[i:j], start_line)
        i = j


PAREN_OPEN = {"(", "[", "{"}
PAREN_CLOSE = {")", "]", "}"}
MATCH = {"(": ")", "[": "]", "{": "}", ")": "(", "]": "[", "}": "{"}


def find_forms(path):
    text = path.read_text(encoding="utf-8")
    total_lines = text.count("\n") + (1 if not text.endswith("\n") else 0)
    tokens = list(tokenize(text))
    forms = []
    stack = []
    form_start = None
    form_start_idx = None
    for idx, (tok, line) in enumerate(tokens):
        if tok in PAREN_OPEN:
            if not stack:
                form_start = line
                form_start_idx = idx
            stack.append((tok, line))
        elif tok in PAREN_CLOSE:
            if stack and MATCH.get(stack[-1][0]) == tok:
                stack.pop()
                if not stack and form_start is not None:
                    end_line = line
                    forms.append((form_start, end_line, tokens[form_start_idx : idx + 1]))
                    form_start = None
                    form_start_idx = None
    return total_lines, forms


def is_defn_form(tokens):
    if len(tokens) < 2:
        return False
    # tokens[0] is opening paren, tokens[1] is macro/var type
    name = tokens[1][0]
    return name in {"defn", "defn-", "defmacro", "defmacro-"}


def function_name(tokens):
    if len(tokens) >= 3:
        return tokens[2][0]
    return "<anonymous>"


def main():
    ns_violations = []
    fn_violations = []
    file_count = 0
    fn_count = 0

    for src_dir in SRC_DIRS:
        if not src_dir.exists():
            continue
        for path in sorted(src_dir.rglob("*.clj*")):
            rel = path.relative_to(ROOT)
            file_count += 1
            total_lines, forms = find_forms(path)
            if total_lines > NS_LIMIT:
                ns_violations.append((str(rel), total_lines))
            for start, end, tokens in forms:
                if is_defn_form(tokens):
                    fn_count += 1
                    length = end - start + 1
                    if length > FN_LIMIT:
                        fn_violations.append(
                            (str(rel), function_name(tokens), start, length)
                        )

    print("=" * 70)
    print(f"Checked {file_count} source files")
    print(f"Found {fn_count} functions/macros")
    print("=" * 70)

    print(f"\nNamespaces exceeding {NS_LIMIT} lines: {len(ns_violations)}")
    if ns_violations:
        for rel, lines in sorted(ns_violations, key=lambda x: -x[1]):
            print(f"  {lines:4d}  {rel}")
    else:
        print("  None")

    print(f"\nFunctions/macros exceeding {FN_LIMIT} lines: {len(fn_violations)}")
    if fn_violations:
        for rel, name, start, length in sorted(fn_violations, key=lambda x: -x[3]):
            print(f"  {length:4d}  {rel}:{start}  {name}")
    else:
        print("  None")

    print()
    if ns_violations or fn_violations:
        print("RESULT: Constraints NOT fully satisfied.")
        sys.exit(1)
    else:
        print("RESULT: All checked sources satisfy the constraints.")


if __name__ == "__main__":
    main()
