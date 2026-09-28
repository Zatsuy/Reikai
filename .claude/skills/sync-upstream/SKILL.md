---
name: sync-upstream
description: Merge upstream Reikai's newest development branch into the fork - check what is new, merge with fork-owned paths kept, resolve real conflicts hunk by hunk, carry over useful upstream knowledge, verify, push. Use when the session start reports a failed Upstream sync run, when upstream has commits the Monday sync has not merged, or when the owner asks.
effort: high
---
Reikai is the fork's upstream (decision D-001); Mihon arrives through it. Background:
`docs/fork/upstream-sync.md`.

1. `scripts/fork/sync_upstream.py --check`. Nothing new: report and stop.
2. Commit or stash your own work, then `scripts/fork/sync_upstream.py`. It merges the newest
   `feat/<version>` branch (or `main` once a release absorbed it), keeps every fork-owned path as
   the fork's, and commits when nothing else conflicts.
3. **Real conflicts** (exit 1): resolve hunk by hunk. Keep upstream's change and re-apply the
   fork's fenced seam inside it; never drop a `FORK -->` fence, never take a whole side of a file
   that holds a seam, never `git merge --abort` to escape. Then `git add` and `git commit --no-edit`.
4. **Carry knowledge over.** The script lists upstream's changes to fork-owned paths. Read
   upstream's changes to `.claude/rules/` and `CLAUDE.md` (`git diff <old>..<new> -- <path>` on
   upstream's branch): a changed convention (DI, screens, migrations) becomes a small edit to the
   fork's `.claude/rules/*.md`, logged in `docs/fork/harness-log.md`. New upstream workflows are
   dropped automatically; the fork runs none of them.
5. **Verify**: `scripts/fork/gw :app:compileDebugKotlin`, then `scripts/fork/gw :app:testDebugUnitTest
   :jp-yomitan:testDebugUnitTest` (the full suites: a merge is cross-cutting),
   `node --test jp-yomitan/src/test/js/*.test.mjs`, `python3 -m unittest scripts/fork/test_yomitan_bump.py`,
   `scripts/fork/gw :data:verifySqlDelightMigration`, `scripts/fork/seams.py --check`. Fork code
   broken by an upstream API change is fixed in fork files, not by widening a seam.
6. **Push** `origin main`, add a line under *Upstream syncs* in `docs/fork/ROADMAP.md` (date,
   upstream branch and short SHA), and report: how many upstream commits, anything notable for the
   owner (new features they will see), anything that needed a decision.
