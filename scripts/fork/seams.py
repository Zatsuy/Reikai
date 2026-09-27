#!/usr/bin/env python3
"""List the fork's seams: fork edits inside upstream-owned files.

Two forms, always inside a comment at the start of a line:
  block   `// FORK -->` ... `// FORK <--`   (any of // # /* * as the comment start)
  note    `// FORK: why` or `<!-- FORK: why -->` directly above a single changed line or element
          (XML comments cannot contain "--", so XML uses notes only)

Every seam is a place a future upstream merge can conflict, so the count should stay small.
Fork-owned paths (scripts/fork/owned-paths.txt) are not scanned: everything in them is the fork's.

Usage:
  scripts/fork/seams.py            table of files and seam counts
  scripts/fork/seams.py --check    exit 1 if a block fence is unbalanced or nested
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMENT = r"^\s*(//|#|/\*|\*|<!--)\s*"
OPEN = re.compile(COMMENT + r"FORK -->")
CLOSE = re.compile(COMMENT + r"FORK <--")
NOTE = re.compile(COMMENT + r"FORK:")


def owned_patterns():
    text = (ROOT / "scripts/fork/owned-paths.txt").read_text().splitlines()
    return [l.strip() for l in text if l.strip() and not l.startswith("#")]


def is_owned(path, patterns):
    return any(path == p or (p.endswith("/") and path.startswith(p)) for p in patterns)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    grep = subprocess.run(["git", "grep", "-l", "-I", "-E", r"FORK (-->|<--)|FORK:", "--", "."],
                          cwd=ROOT, capture_output=True, text=True)
    patterns = owned_patterns()
    files = [f for f in grep.stdout.split() if not is_owned(f, patterns)]
    problems, rows = [], []
    for f in files:
        depth = blocks = notes = 0
        for n, line in enumerate((ROOT / f).read_text(errors="replace").splitlines(), 1):
            if OPEN.match(line):
                depth += 1
                blocks += 1
                if depth > 1:
                    problems.append(f"{f}:{n}: nested FORK fence")
            elif CLOSE.match(line):
                depth -= 1
                if depth < 0:
                    problems.append(f"{f}:{n}: FORK <-- without an opening fence")
                    depth = 0
            elif NOTE.match(line):
                notes += 1
        if depth:
            problems.append(f"{f}: {depth} FORK fence(s) never closed")
        if blocks or notes:
            rows.append((blocks + notes, blocks, notes, f))
    for total, blocks, notes, f in sorted(rows, reverse=True):
        print(f"{total:3d}  {f}  ({blocks} blocks, {notes} notes)")
    print(f"{sum(r[0] for r in rows)} seams in {len(rows)} upstream files")
    for p in problems:
        print(f"PROBLEM {p}")
    return 1 if (args.check and problems) else 0


if __name__ == "__main__":
    sys.exit(main())
