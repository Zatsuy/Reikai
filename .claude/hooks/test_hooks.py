#!/usr/bin/env python3
"""Fixtures for the fork's hooks. Run: python3 .claude/hooks/test_hooks.py (exit 1 on failure)."""
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

BASH = [
    ("git push origin main", None),
    ("git push upstream main", "deny"),
    ("git push --force origin main", "deny"),
    ("git push -f origin HEAD", "deny"),
    ("cat app/google-services.json", "deny"),
    ("grep -rn google-services app/build.gradle.kts", None),
    ("sudo dnf install java-21-openjdk-devel", "deny"),
    ("dnf install jq", "deny"),
    ("./gradlew :app:assembleRelease -Pdist=github", "deny"),
    ("./gradlew :app:compileDebugKotlin", None),
    ("sleep 5 && git status", None),
    ("sleep 60", "deny"),
    ("while ! adb devices | grep device; do sleep 2; done", "deny"),
    ("git reset --hard HEAD~1", "ask"),
    ("git clean -fdx", "ask"),
    ("rm -rf build/fork-logs", None),
    ("rm -rf /tmp/claude-1000/x/scratchpad/yt", None),
    ("rm -rf app/src/main/java/reikai", "ask"),
    ("rm file.txt", None),
    ("sdkmanager --install 'ndk;29.0.14206865'", "ask"),
]

EDIT = [
    (str(ROOT / "app/google-services.json"), "deny"),
    (str(ROOT / ".claude/settings.json"), "ask"),
    (str(ROOT / ".claude/hooks/guard_bash.py"), "ask"),
    (str(ROOT / "docs/fork/ROADMAP.md"), None),
    (str(ROOT / "app/build.gradle.kts"), "allow"),  # upstream-owned: allowed with a reminder
    (str(ROOT / "app/src/main/java/jp/reikai/Example.kt"), None),
    ("/tmp/somewhere/else.txt", None),
]


def decision(hook, payload):
    out = subprocess.run([sys.executable, str(HERE / hook)], input=json.dumps(payload),
                         capture_output=True, text=True, timeout=10)
    if out.returncode != 0:
        return f"crash: {out.stderr.strip()[:200]}"
    if not out.stdout.strip():
        return None
    return json.loads(out.stdout)["hookSpecificOutput"]["permissionDecision"]


def main():
    failures = 0
    for cmd, want in BASH:
        got = decision("guard_bash.py", {"tool_name": "Bash", "tool_input": {"command": cmd}})
        if got != want:
            failures += 1
            print(f"FAIL bash {cmd!r}: want {want}, got {got}")
    for path, want in EDIT:
        got = decision("guard_edit.py", {"tool_name": "Edit", "tool_input": {"file_path": path}})
        if got != want:
            failures += 1
            print(f"FAIL edit {path}: want {want}, got {got}")
    start = subprocess.run([sys.executable, str(HERE / "session_start.py")], capture_output=True,
                           text=True, timeout=15)
    if start.returncode != 0 or "Reikai JP." not in start.stdout:
        failures += 1
        print(f"FAIL session_start: {start.stderr.strip()[:200]}")
    total = len(BASH) + len(EDIT) + 1
    print(f"{total - failures}/{total} hook fixtures pass")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
