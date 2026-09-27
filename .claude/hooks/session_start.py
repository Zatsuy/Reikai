#!/usr/bin/env python3
"""SessionStart: a few lines of state so a fresh agent starts cold, cheaply.

GitHub automation pushes to main on its own (decision D-015), so this also fetches origin and asks
GitHub's public API whether an automated run failed: that is how a failure reaches the next agent
session without anyone watching. The network calls run in parallel with short timeouts and stay
silent when offline.
"""
import json
import re
import subprocess
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Workflow file -> (name for the agent, what to do when its latest run on main failed)
WATCHED = {
    "fork-upstream-sync.yml": ("Upstream sync", "run /sync-upstream"),
    "fork-release.yml": ("App release", "run /debug on the failed run"),
    "fork-ci.yml": ("Fork CI on main", "run /debug on the failed run"),
}


def run(*cmd, timeout=3):
    try:
        out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
        return out.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def fetch_origin():
    run("git", "fetch", "--quiet", "origin", "main", timeout=5)


def failed_automation():
    url = run("git", "remote", "get-url", "origin")
    m = re.search(r"github\.com[:/]([^/]+/[^/.]+)", url)
    if not m:
        return []
    api = f"https://api.github.com/repos/{m.group(1)}/actions/runs?branch=main&per_page=40"
    try:
        req = urllib.request.Request(api, headers={"Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=4) as resp:
            runs = json.load(resp).get("workflow_runs", [])
    except Exception:
        return []
    latest = {}
    for r in runs:  # newest first
        name = Path(r.get("path", "")).name
        if name in WATCHED and name not in latest and r.get("event") != "pull_request":
            latest[name] = r
    lines = []
    for name, r in latest.items():  # a newer run still in progress supersedes an older failure
        if r.get("status") == "completed" and r.get("conclusion") in ("failure", "timed_out", "startup_failure"):
            label, action = WATCHED[name]
            lines.append(f"GitHub automation failed: {label} on {r['created_at'][:10]} ({r['html_url']}); {action}.")
    return lines


def devices():
    found = []
    for line in run("adb", "devices", "-l", timeout=2).splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        model = next((p.split(":", 1)[1] for p in parts if p.startswith("model:")), parts[0])
        kind = run("adb", "-s", parts[0], "shell", "getprop", "ro.build.characteristics", timeout=2)
        found.append(f"{'tablet' if 'tablet' in kind else 'phone'} {model}")
    return found


def main():
    with ThreadPoolExecutor(3) as pool:
        fetched = pool.submit(fetch_origin)
        automation = pool.submit(failed_automation)
        adb = pool.submit(devices)
        fetched.result()
        branch = run("git", "rev-parse", "--abbrev-ref", "HEAD")
        head = run("git", "log", "-1", "--format=%h %s")
        dirty = len([l for l in run("git", "status", "--porcelain").splitlines() if l.strip()])
        ahead = run("git", "rev-list", "--count", "origin/main..HEAD") or "?"
        behind = run("git", "rev-list", "--count", "HEAD..origin/main") or "0"
        cache = ROOT / ".git/fork-upstream-branch"
        up = cache.read_text().strip() if cache.exists() else "upstream/main"
        new_up = run("git", "rev-list", "--count", f"HEAD..{up}") or "?"
        lines = [f"Reikai JP. {branch} @ {head}. Uncommitted files: {dirty}. Unpushed commits: {ahead}."]
        if behind != "0":
            lines.append(f"origin/main has {behind} commits you do not (GitHub automation pushes there): "
                         "run `git pull --ff-only` before working.")
        lines.append(f"Upstream {up}: {new_up} commits not merged yet (as of the last fetch; "
                     "the Upstream sync workflow merges them every Monday).")

        roadmap = ROOT / "docs/fork/ROADMAP.md"
        if roadmap.exists():
            text = roadmap.read_text()
            now = re.search(r"^## Now\b[^\n]*\n(.*?)(^## |\Z)", text, re.S | re.M)
            item = re.search(r"^- \[ \] (.+)$", now.group(1), re.M) if now else None
            if item:
                lines.append(f"Roadmap next: {item.group(1)[:160]}")
        decisions = ROOT / "docs/fork/decisions.md"
        if decisions.exists():
            open_part = decisions.read_text().split("## Open", 1)[-1].split("## Log", 1)[0]
            lines.append(f"Open owner questions: {len(re.findall(r'^- \*\*O-', open_part, re.M))}.")
        if not (ROOT / ".git/hooks/commit-msg").exists():
            lines.append("Git hooks not installed: run scripts/fork/install-githooks.sh")
        lines += automation.result()
        found = adb.result()
        lines.append(f"Devices over adb: {', '.join(found)}." if found else
                     "Devices over adb: none (docs/fork/testing/phone-setup.md).")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
