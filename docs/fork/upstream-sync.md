# Staying in sync with upstream

Reikai JP follows Reikai with plain `git merge` (decision D-001). Reikai itself ports Mihon by
hand, so Mihon's changes reach the fork through Reikai with no extra work.

## Which branch

Upstream develops on `feat/<version>` and merges it into `main` at each release. The fork merges
the highest `feat/X.Y.Z` branch, or `main` once a release has absorbed it, plus any hotfix that
lands on `main` first. `scripts/fork/sync_upstream.py` works this out on every run, so a new
`feat/0.5.0` is picked up without anyone changing configuration.

## Who owns what

- **Fork-owned** (listed in `scripts/fork/owned-paths.txt`): `AGENTS.md`, `CLAUDE.md`, `LICENSE`,
  `LICENSES/`, `.claude/`, `.github/README.md`, `.github/workflows/`, `docs/fork/`, `scripts/fork/`,
  and fork code under `app/src/main/java/jp/reikai/`. On a merge the fork's version always wins
  and upstream's changes there are reported for review, not applied.
- **Upstream-owned**: everything else, including upstream's `README.md`, `ROADMAP.md`,
  `CHANGELOG.md` and `docs/dev/`. The fork edits these only as **seams**: the smallest possible
  change, fenced `// FORK -->` ... `// FORK <--`, or a one-line `// FORK: why` comment above a
  single changed line (XML comments cannot contain `--`, so XML uses `<!-- FORK: why -->`).
  `scripts/fork/seams.py` lists them; fewer is better, because every seam is a place a merge can
  conflict.

## How a sync runs

1. `scripts/fork/sync_upstream.py --check` shows the new commits, the conflicts it will resolve
   itself (fork-owned paths) and the real ones.
2. `scripts/fork/sync_upstream.py` merges, restores fork-owned paths, and commits when nothing else
   conflicts. Real conflicts are resolved by hand, keeping upstream's change and re-applying the
   fork's fenced seam inside it.
3. Verify (compile, full unit tests, migrations, seams) and push. The `/sync-upstream` skill runs
   all of this; the weekly `Upstream watch` workflow does steps 1 and 2 on GitHub and opens a pull
   request when the merge is clean.

## Carrying knowledge over

Upstream keeps technical conventions in its own `CLAUDE.md` and `.claude/rules/`, which the fork
replaced with a smaller set. When a sync reports upstream changes there, the agent reads them and
brings anything that affects how fork code must be written (a DI change, a new screen convention,
a migration rule) into the fork's `.claude/rules/`, logged in `harness-log.md`.

## Contributing back

Generic fixes the fork makes to upstream code (a plugin-host bug, a missing filter) are worth
offering upstream: the seam disappears once upstream has the fix. Upstream welcomes pull requests
but prefers a discussion first for anything beyond a small fix (`CONTRIBUTING.md`). Only code the
fork wrote itself and that contains no Yomitan-derived material can be offered under upstream's
Apache-2.0 licence. The owner opens those pull requests; agents prepare the branch and the text.
