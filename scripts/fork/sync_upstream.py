#!/usr/bin/env python3
"""Merge upstream Reikai's newest development branch into the fork.

Usage:
  scripts/fork/sync_upstream.py --check     report what a merge would bring; touches nothing
  scripts/fork/sync_upstream.py             merge, keep fork-owned paths, commit when clean
  scripts/fork/sync_upstream.py --no-commit merge and resolve owned paths, leave the commit to you

Exit codes: 0 done or nothing to do, 1 real conflicts remain (merge left in progress),
2 refused (dirty tree, missing remote).
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OWNED_FILE = ROOT / "scripts/fork/owned-paths.txt"
BRANCH_CACHE = ROOT / ".git/fork-upstream-branch"  # local state, read by the hooks
REMOTE = "upstream"


def git(*args, check=True):
    out = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed:\n{out.stderr.strip()}")
    return out


def owned_patterns():
    lines = OWNED_FILE.read_text().splitlines()
    return [l.strip() for l in lines if l.strip() and not l.startswith("#")]


def is_owned(path, patterns):
    return any(path == p or (p.endswith("/") and path.startswith(p)) for p in patterns)


def newest_dev_branch():
    """The highest feat/X.Y.Z branch unless main has already absorbed it."""
    refs = git("for-each-ref", "--format=%(refname:short)", f"refs/remotes/{REMOTE}/feat/").stdout.split()
    versions = []
    for ref in refs:
        m = re.fullmatch(rf"{REMOTE}/feat/(\d+)\.(\d+)\.(\d+)", ref)
        if m:
            versions.append((tuple(int(x) for x in m.groups()), ref))
    main = f"{REMOTE}/main"
    if not versions:
        return main
    feat = max(versions)[1]
    if git("merge-base", "--is-ancestor", feat, main, check=False).returncode == 0:
        return main
    return feat


def commits_between(base, tip):
    return git("rev-list", "--count", f"{base}..{tip}").stdout.strip()


def conflicts_preview(target, patterns):
    """Predict conflicts with merge-tree, split into owned (auto-resolved) and real."""
    out = git("merge-tree", "--write-tree", "--name-only", "--no-messages", "HEAD", target, check=False)
    lines = out.stdout.splitlines()[1:]  # first line is the tree id
    files = [l for l in lines if l.strip()]
    owned = [f for f in files if is_owned(f, patterns)]
    real = [f for f in files if not is_owned(f, patterns)]
    return owned, real


def upstream_changes_to_owned(target, patterns):
    base = git("merge-base", "HEAD", target).stdout.strip()
    changed = git("diff", "--name-only", "--no-renames", base, target).stdout.split()
    return [f for f in changed if is_owned(f, patterns)]


def resolve_owned(patterns):
    """After `git merge --no-commit`, put every owned path back to the fork's version."""
    touched = set(git("diff", "--name-only", "--no-renames", "HEAD").stdout.split())
    touched |= set(git("diff", "--name-only", "--cached", "--no-renames", "HEAD").stdout.split())
    touched |= set(git("diff", "--name-only", "--diff-filter=U").stdout.split())
    for path in sorted(p for p in touched if is_owned(p, patterns)):
        in_head = git("cat-file", "-e", f"HEAD:{path}", check=False).returncode == 0
        if in_head:
            git("checkout", "HEAD", "--", path)
        else:
            git("rm", "-q", "-f", "--cached", "--ignore-unmatch", "--", path)
            (ROOT / path).unlink(missing_ok=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--no-commit", action="store_true")
    args = ap.parse_args()

    if git("remote", "get-url", REMOTE, check=False).returncode != 0:
        print(f"No '{REMOTE}' remote. Add it: git remote add upstream https://github.com/unseensnick/Reikai.git")
        return 2
    git("fetch", "--quiet", "--prune", REMOTE)
    patterns = owned_patterns()
    targets = [newest_dev_branch()]
    main_ref = f"{REMOTE}/main"
    if targets[0] != main_ref:
        targets.append(main_ref)  # a hotfix on main that the dev branch has not taken yet
    BRANCH_CACHE.write_text(targets[0] + "\n")

    pending = [t for t in targets if int(commits_between("HEAD", t)) > 0]
    print(f"Upstream development branch: {targets[0]}")
    if not pending:
        print("Nothing new upstream. The fork is up to date.")
        return 0

    for target in pending:
        owned, real = conflicts_preview(target, patterns)
        changed_owned = upstream_changes_to_owned(target, patterns)
        print(f"\n{target}: {commits_between('HEAD', target)} new commits")
        print(f"  conflicts in fork-owned paths (auto-resolved): {len(owned)}")
        print(f"  real conflicts: {len(real)}" + ("".join(f"\n    {f}" for f in real)))
        if changed_owned:
            print("  upstream changed fork-owned paths (review for knowledge worth carrying over):")
            for f in changed_owned[:40]:
                print(f"    {f}")
            if len(changed_owned) > 40:
                print(f"    ... and {len(changed_owned) - 40} more")
    if args.check:
        return 0

    if git("status", "--porcelain", "--untracked-files=no").stdout.strip():
        print("\nRefused: commit or stash your changes first.")
        return 2

    for target in pending:
        subjects = git("log", "--format=%s", "--no-merges", f"HEAD..{target}").stdout.splitlines()
        merged = git("merge", "--no-ff", "--no-commit", target, check=False)
        resolve_owned(patterns)
        real = git("diff", "--name-only", "--diff-filter=U").stdout.split()
        if real:
            print(f"\nMerge of {target} is in progress with {len(real)} real conflicts:")
            for f in real:
                print(f"  {f}")
            print("Resolve them hunk by hunk (keep upstream's change and the fork's fenced seam),"
                  " then `git add` and `git commit --no-edit`.")
            return 1
        if merged.returncode != 0 and "conflict" not in (merged.stdout + merged.stderr).lower():
            sys.exit(merged.stderr)
        if args.no_commit:
            print(f"\nMerged {target} (not committed). Review, verify, then `git commit --no-edit`.")
            return 0
        short = git("rev-parse", "--short", target).stdout.strip()
        notable = [s for s in subjects if re.match(r"(feat|fix|perf)\b", s)][:15]
        body = [f"Brings {len(subjects)} upstream commits. Fork-owned paths kept as the fork's."]
        if notable:
            body += ["", "Notable:"] + [f"- {s}" for s in notable]
        name = target.split("/", 1)[1]
        msg = f"chore(sync): merge upstream Reikai {name} ({short})\n\n" + "\n".join(body) + "\n"
        commit = subprocess.run(["git", "commit", "-q", "-F", "-"], cwd=ROOT, input=msg, text=True,
                                capture_output=True)
        if commit.returncode != 0:
            sys.exit(commit.stderr or commit.stdout)
        print(f"\nMerged and committed {target} as {git('rev-parse', '--short', 'HEAD').stdout.strip()}.")
    print("Next: verify (compile + tests), check `scripts/fork/seams.py --check`, then push.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
