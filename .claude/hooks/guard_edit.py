#!/usr/bin/env python3
"""PreToolUse guard for Edit/Write/NotebookEdit.

- Refuses secrets.
- Asks the owner before harness safety surfaces change (settings, hooks, git hooks): agents may
  propose those changes, only the owner approves them (docs/fork/harness.md).
- Reminds, without blocking, that a file is upstream-owned and must be edited as a fenced seam.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SECRETS = re.compile(r"(^|/)(google-services\.json|keystore\.properties|\.env(\.[\w-]+)?|secrets/.*|[^/]+\.(jks|keystore|pem|key))$")
SAFETY = re.compile(r"^(\.claude/settings(\.local)?\.json|\.claude/hooks/.*|scripts/fork/githooks/.*)$")


def owned(rel):
    lines = (ROOT / "scripts/fork/owned-paths.txt").read_text().splitlines()
    patterns = [l.strip() for l in lines if l.strip() and not l.startswith("#")]
    return any(rel == p or (p.endswith("/") and rel.startswith(p)) for p in patterns)


def in_upstream(rel):
    cache = ROOT / ".git/fork-upstream-branch"
    ref = cache.read_text().strip() if cache.exists() else "upstream/main"
    try:
        out = subprocess.run(["git", "cat-file", "-e", f"{ref}:{rel}"], cwd=ROOT,
                             capture_output=True, timeout=3)
        return out.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def emit(decision, reason, context=None):
    out = {"hookEventName": "PreToolUse", "permissionDecision": decision,
           "permissionDecisionReason": f"[fork-guard] {reason}"}
    if context:
        out["additionalContext"] = context
    print(json.dumps({"hookSpecificOutput": out}))


def main():
    data = json.load(sys.stdin)
    tool_input = data.get("tool_input") or {}
    path = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
    if not path:
        return 0
    p = Path(path)
    try:
        rel = str(p.resolve().relative_to(ROOT))
    except ValueError:
        return 0  # outside the repo (scratch files, memory): not this hook's business
    if SECRETS.search(rel):
        emit("deny", "Secrets (signing keys, Firebase config, .env) are never written by agents.")
    elif SAFETY.match(rel):
        emit("ask", f"{rel} is a harness safety surface; the owner approves changes to it.")
    elif not owned(rel) and in_upstream(rel):
        emit("allow", "upstream-owned file",
             f"{rel} is upstream-owned (Reikai). Keep the edit minimal and fence it with "
             "`FORK -->` / `FORK <--` comments in the file's comment style, or move the logic to a "
             "fork file under jp.reikai. Check with scripts/fork/seams.py --check.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
