#!/usr/bin/env python3
"""SessionStart: a few lines of state so a fresh agent starts cold, cheaply (no network)."""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def run(*cmd, timeout=3):
    try:
        out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
        return out.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def main():
    branch = run("git", "rev-parse", "--abbrev-ref", "HEAD")
    head = run("git", "log", "-1", "--format=%h %s")
    dirty = len([l for l in run("git", "status", "--porcelain").splitlines() if l.strip()])
    ahead = run("git", "rev-list", "--count", "origin/main..HEAD") or "?"
    cache = ROOT / ".git/fork-upstream-branch"
    up = cache.read_text().strip() if cache.exists() else "upstream/main"
    new_up = run("git", "rev-list", "--count", f"HEAD..{up}") or "?"
    lines = [f"Reikai JP. {branch} @ {head}. Uncommitted files: {dirty}. Unpushed commits: {ahead}.",
             f"Upstream {up}: {new_up} commits not merged yet (as of the last fetch)."]

    roadmap = ROOT / "docs/fork/ROADMAP.md"
    if roadmap.exists():
        text = roadmap.read_text()
        now = re.search(r"^## Now\n(.*?)(^## |\Z)", text, re.S | re.M)
        item = re.search(r"^- \[ \] (.+)$", now.group(1), re.M) if now else None
        if item:
            lines.append(f"Roadmap next: {item.group(1)[:160]}")
    decisions = ROOT / "docs/fork/decisions.md"
    if decisions.exists():
        open_part = decisions.read_text().split("## Open", 1)[-1].split("## Log", 1)[0]
        lines.append(f"Open owner questions: {len(re.findall(r'^- \*\*O-', open_part, re.M))}.")
    if not (ROOT / ".git/hooks/commit-msg").exists():
        lines.append("Git hooks not installed: run scripts/fork/install-githooks.sh")
    devices = run("adb", "devices", timeout=2).splitlines()[1:]
    phone = any(l.strip().endswith("device") for l in devices)
    lines.append(f"Phone over adb: {'connected' if phone else 'not connected'}.")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
