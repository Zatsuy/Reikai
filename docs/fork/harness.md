# The harness

How the agents that build Reikai JP are organised: what they read, which procedures they follow,
what guards them, and how the harness improves itself. Change this page in the same commit as the
harness.

## The idea

Every tool call re-sends an agent's whole context, so a job costs roughly *turns times context*.
The harness therefore keeps contexts small and single-purpose, puts **control flow in code**
(scripts, hooks) and **judgement in models**, checks claims with something deterministic, and
changes itself only on measurements. Research behind these choices:
[research/landscape-2026-09.md](research/landscape-2026-09.md), *Harness research*.

## What an agent reads, and when

| Layer | Loaded | Size budget |
|---|---|---|
| `AGENTS.md` (via `CLAUDE.md`) | every turn of every agent | under 120 lines; pruned first |
| `.claude/rules/*.md` | only when the agent opens a matching file (`paths:`) | under 60 lines each |
| `.claude/skills/*/SKILL.md` | only when a procedure runs | under 60 lines each |
| `.claude/agents/*.md` | the system prompt of a fresh subagent | short checklists |
| `docs/fork/*` | read on demand | as long as useful |

Upstream Reikai's harness loaded about 110 KB into every turn; this one loads under 10 KB.

## The procedures (skills)

| Start with | For | Checked by |
|---|---|---|
| `/next` | the next roadmap item, end to end | `/verify`, `/review`, the retro line |
| `/grill <request>` | an idea or request from the owner | the owner's answers in `decisions.md` |
| `/scout <task>` | investigate and plan before building | citations, a refutation pass |
| `/debug <symptom>` | a bug | a failing-then-passing check |
| `/verify` | evidence before any "done" | the commands it runs |
| `/review` | fresh-eyes review of a diff | two independent reviewers |
| `/sync-upstream` | merge upstream Reikai | compile, full tests, seams check |
| `/owner-steps` | instructions for the owner's hands | reachability in the current build |
| `/retro` | measure and improve the harness | `scripts/fork/retro.py` thresholds |

Subagent roles (model and effort pinned): `reviewer` and `spec-checker` (read-only, fresh eyes),
`verifier` (runs builds, returns a summary). Built-in Explore agents do broad reading.

## Guards

| Guard | Refuses | Asks the owner | Where |
|---|---|---|---|
| Bash hook | pushing to upstream, force-push, reading secrets, system installs, telemetry builds, polling waits | reset --hard, clean -f, discarding all changes, deleting branches or stashes, recursive deletes outside build and scratch folders, installing SDK packages | `.claude/hooks/guard_bash.py` |
| Edit hook | writing secrets | changes to settings, hooks, git hooks | `.claude/hooks/guard_edit.py` |
| Edit hook (note) | nothing | nothing: reminds that a file is upstream-owned and must be edited as a fenced seam | same |
| Git hooks | bad commit subjects, AI credit lines, bare `#N`, em dashes, unbalanced FORK fences, staged secrets | nothing | `scripts/fork/githooks/` |
| Session start | nothing | nothing: prints branch, upstream status, next roadmap item, open questions, phone connection | `.claude/hooks/session_start.py` |

Every refusal reason starts with `[fork-guard]` so the retro can count them. A refusal that blocks
legitimate work is a harness defect to fix (class A), never something to work around. Hooks run
as `python3 <script>`, so a lost executable bit cannot silently switch them off (upstream's shell
hooks never ran on Linux for that reason). Fixtures: `python3 .claude/hooks/test_hooks.py`.

## Self-improvement

`scripts/fork/retro.py` reads the Claude Code transcripts of this repo (main sessions and
subagents) and measures: API calls, tokens by kind, estimated dollars, peak context, tool calls and
errors, hook errors and refusals, Gradle runs and failures, `--help` lookups, polling waits, cache
gaps over an hour, questions to the owner. It compares them with
`scripts/fork/retro-thresholds.json`. `/next` records one entry per roadmap item; `/retro` looks at
a week and acts. Records accumulate in `docs/fork/harness/retro-log.jsonl`.

Each crossed threshold is handled by class (the owner's rule from KanjiWeave):

- **A, mechanics** (scripts, hooks, settings, paths, missing commands): fixed at once, with a test;
  kept or reverted on the next retro's number.
- **B, instructions** (skills, rules, AGENTS.md): the smallest edit, logged as a `canary` in
  [harness-log.md](harness-log.md) with the metric to watch; the next retro keeps or reverts it.
- **C, owner-facing** (product behaviour, how the owner works, safety surfaces such as permissions
  and hook refusals): an *Open* decision with evidence and options; never applied without the
  owner.

Why gated rather than free: developers feel faster while measuring slower (METR); generated
context files lower success and raise cost (ETH Zurich); regenerating instructions collapses them
while small curated edits help (ACE). So additions need a measurement, deletions are proposed as
often as additions, and edits are targeted, never whole-file rewrites.

The first baseline (2026-09-27, the research and setup session before this harness existed):
1,417 hook errors from upstream's broken hooks, a 565k-token peak context, 4 polling waits, about
$78 with 22 research subagents. The next retro compares against it.

## Models, effort, cache

- **Opus 5.5 everywhere** (decision D-012): `.claude/settings.json` points the `haiku` and `sonnet`
  aliases at Opus 5.5 so built-in agents and background tasks follow; roles pin `model: opus`.
  Easy steps get lower *effort*, not a smaller model.
- Skills pin their effort (`high` for judgement, `medium` or `low` for mechanical steps).
- Subagent prompt cache lives an hour (`subagentPromptCacheTtl`), matching the subscription cache.

## Continuous integration

The fork runs only its own workflows (decision D-013):
- `fork-ci.yml`: harness fixtures, seams, format, DI ownership, migrations, compile and unit tests
  on pull requests and code pushes to `main` (not on the sync's own merges, already checked). No
  secrets, publishes nothing. Pushes to `main` save the Gradle cache so later builds start warm.
- `fork-upstream-sync.yml`: every Monday, merges upstream, runs the same checks and pushes to
  `main` with the `SYNC_DEPLOY_KEY` deploy key. A failure pushes nothing. A keepalive job stops
  GitHub switching the schedule off after 60 quiet days.
- Automation needs none of the owner's attention (decision D-015). A failed run reaches the next
  agent through the session-start hook, which asks GitHub's API for the latest run of each fork
  workflow on `main`.
- Upstream's workflows are deleted, and `sync_upstream.py` drops any new ones on every merge.

## Changing the harness

A change to a skill, rule, hook, agent, script or threshold:
1. starts from a retro number or a concrete failure, stated in the commit;
2. passes `python3 .claude/hooks/test_hooks.py` and `scripts/fork/seams.py --check`;
3. gets a line in [harness-log.md](harness-log.md) (class, evidence, metric, canary or kept);
4. updates this page when the structure changes.
Safety surfaces (settings, hooks, git hooks) need the owner's approval; the edit hook asks.
