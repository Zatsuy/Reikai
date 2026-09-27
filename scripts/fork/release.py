#!/usr/bin/env python3
"""Helpers for .github/workflows/fork-release.yml, the fork's self-publishing app release.

Releases are tagged r<commit count of main>, the number the nightly build compiles in as
COMMIT_COUNT, so the in-app updater installs any release whose number is higher than its own.

Usage:
  scripts/fork/release.py check          key=value lines for $GITHUB_OUTPUT: tag, previous, skip, reason
  scripts/fork/release.py notes <prev>   the release body (Markdown); <prev> may be empty
  scripts/fork/release.py prune <keep>   delete all but the newest <keep> releases and their tags

check and prune call `gh` on GITHUB_REPOSITORY (default Zatsuy/Reikai), so they need GH_TOKEN.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Only a change under one of these can alter the built APK (upstream's nightly.yml allow-list).
# The release workflow counts too: its Gradle flags shape the APK.
APP_PATHS = [":(glob)**/src/**", ":(glob)**/*.kts", ":(glob)**/*.pro", "gradle/", "gradle.properties",
             "gradlew", ".github/workflows/fork-release.yml"]
REPO = os.environ.get("GITHUB_REPOSITORY", "Zatsuy/Reikai")
DOCS = f"https://github.com/{REPO}/blob/main/docs/fork"
GUIDE = f"{DOCS}/install.md"
MAX_UPSTREAM = 15


def sh(*cmd, check=True):
    out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit(f"{' '.join(cmd)} failed:\n{out.stderr.strip()}")
    return out


def release_tags():
    """Published (not draft) release tags, highest number first."""
    out = sh("gh", "release", "list", "--repo", REPO, "--exclude-drafts", "--limit", "200", "--json", "tagName").stdout
    tags = [r["tagName"] for r in json.loads(out or "[]") if re.fullmatch(r"r\d+", r["tagName"])]
    return sorted(tags, key=lambda t: int(t[1:]), reverse=True)


def check():
    count = int(sh("git", "rev-list", "--count", "HEAD").stdout)
    tags = release_tags()
    previous = tags[0] if tags else ""
    skip, reason = False, "first release" if not previous else "app files changed"
    if previous:
        if int(previous[1:]) >= count:
            skip, reason = True, f"{previous} is not older than r{count}"
        elif sh("git", "rev-parse", "--verify", "--quiet", f"{previous}^{{commit}}", check=False).returncode:
            previous, reason = "", f"{previous} not found in history, publishing anyway"
        elif sh("git", "diff", "--quiet", previous, "HEAD", "--", *APP_PATHS, check=False).returncode == 0:
            skip, reason = True, f"no app files changed since {previous}"
    print(f"tag=r{count}\nprevious={previous}\nskip={str(skip).lower()}\nreason={reason}")


def clean(subject):
    subject = re.sub(r"^\w+(\([^)]*\))?!?:\s*", "", subject)
    return subject[:1].upper() + subject[1:]


def notes(previous):
    since = f"{previous}..HEAD" if previous else "HEAD"
    # The fork's own changes, as the owner reads them: entries added to its changelog since the last
    # release (an entry is a "- " line plus its indented continuation lines).
    if previous:
        diff = sh("git", "diff", "-U0", previous, "HEAD", "--", "docs/fork/CHANGELOG.md").stdout
        # A hunk header becomes an empty line, so entries from separate hunks never join.
        lines = [line[1:] if line.startswith("+") else "" for line in diff.splitlines()
                 if line.startswith(("+", "@@")) and not line.startswith("+++")]
    else:
        text = (ROOT / "docs/fork/CHANGELOG.md").read_text()
        lines = text.split("## Unreleased", 1)[-1].split("\n## ", 1)[0].splitlines()
    fork = []
    for line in lines:
        if line.startswith("- "):
            fork.append(line)
        elif fork and fork[-1] and line.startswith("  ") and line.strip():
            fork[-1] += " " + line.strip()
        else:
            fork.append(None)  # anything else ends the entry
    # The changelog links its sibling docs relatively; on a release page or in the app's update
    # screen that resolves nowhere, so point them at the repository.
    fork = [re.sub(r"\]\((?!https?://|#)([^)]+)\)", rf"]({DOCS}/\1)", entry) for entry in fork if entry]
    # Upstream's changes arrive through the sync's merge commits: each brings M^1..M^2.
    merges = sh("git", "log", "--first-parent", "--merges", "--format=%H", "--grep=^chore(sync): merge upstream",
                since).stdout.split()
    upstream = []
    for m in merges:
        for s in sh("git", "log", "--no-merges", "--format=%s", f"{m}^1..{m}^2").stdout.splitlines():
            if re.match(r"(feat|fix|perf)\b", s):
                upstream.append(clean(s))
    body = []
    if fork:
        body += ["**Reikai JP**", ""] + fork + [""]
    if upstream:
        body += ["**From upstream Reikai**", ""] + [f"- {s}" for s in upstream[:MAX_UPSTREAM]]
        if len(upstream) > MAX_UPSTREAM:
            body.append(f"- and {len(upstream) - MAX_UPSTREAM} more")
        body.append("")
    if not body:
        body = ["Maintenance and fixes.", ""]
    # The in-app update screen shows the body only up to the last <!--> marker.
    body += ["<!-->", "", f"First install, or the app does not offer updates: [install guide]({GUIDE}). "
             "Most devices want the `arm64-v8a` file; the one without an ABI in its name works everywhere."]
    print("\n".join(body))


def prune(keep):
    for tag in release_tags()[keep:]:
        sh("gh", "release", "delete", tag, "--repo", REPO, "--cleanup-tag", "--yes")
        print(f"Deleted {tag}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "check":
        check()
    elif cmd == "notes":
        notes(sys.argv[2] if len(sys.argv) > 2 else "")
    elif cmd == "prune":
        prune(int(sys.argv[2]))
    else:
        sys.exit(__doc__)
