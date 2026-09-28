# Reikai JP: shared context for every agent

Loaded on every turn of every agent (Claude Code through `CLAUDE.md`, Codex directly), so it stays
short. Read what it points to only when the task needs it.

## What this is

**Reikai JP** is the owner's fork of [Reikai](https://github.com/unseensnick/Reikai) (a Mihon-based
manga and light-novel reader) turning it into a Japanese-immersion reader: Yomitan lookup and Anki
mining while reading, a Japanese reading mode (vertical or horizontal), and a richer novel reader.
GPL-3.0-or-later, public, not for sale. Fork repo: `Zatsuy/Reikai-JP` (`origin`); upstream:
`unseensnick/Reikai` (`upstream`), tracked at its newest `feat/<version>` branch.

**Where things stand:** *Now* in [docs/fork/ROADMAP.md](docs/fork/ROADMAP.md). **What the owner
decided:** [docs/fork/decisions.md](docs/fork/decisions.md) (never ask what is answered there).
**How it fits together:** [docs/fork/architecture.md](docs/fork/architecture.md).

## The owner

Not a professional developer. Wants agents to run development themselves and to be asked only
what only they can answer or do (AskUserQuestion, grouped, options with a recommendation).
Automation must never need their routine attention (D-015): updates apply themselves when checks
pass, failures wait quietly for the next agent session; never create a chore, review or pull
request for them. They read mostly on a tablet (D-016). Devices are plugged in only on request: when a check
needs the tablet or phone and adb does not list it, ask the owner to connect it and wait (D-019).
Explain in plain English without dumbing down. When a request does not fit the evidence, say so
and suggest the better option. Performance and smoothness of the app are a pillar.

## Read first, by task

| Task | Start with |
|---|---|
| Continue the project / "next" | `/next` |
| An idea or request from the owner | `/grill` |
| Anything non-trivial before building | `/scout` |
| A bug | `/debug` |
| Before claiming anything works | `/verify`, then `/review` for an item's diff |
| Something only the owner can do | `/owner-steps` |
| Upstream has new commits | `/sync-upstream` |
| Cost, speed or harness friction | `/retro`; how the harness works: [docs/fork/harness.md](docs/fork/harness.md) |
| Kotlin, database, tests, WebView code | the matching `.claude/rules/*.md` loads when you open such files |
| Research already done | [docs/fork/research/](docs/fork/research/) |

## Hard rules

1. **Upstream files are upstream's.** Fork code goes in fork places: package `jp.reikai`, fork
   modules, `docs/fork/`, `scripts/fork/` (full list: `scripts/fork/owned-paths.txt`). Editing an
   upstream file is a seam: minimal, fenced `// FORK -->` ... `// FORK <--` (or a one-line
   `FORK: why` comment above a single line; XML uses `<!-- FORK: why -->`), counted by
   `scripts/fork/seams.py`. Never touch upstream's `// RK` markers, its `.sqm` migration sequence,
   its backup proto, `AppGraph.kt`, `ROADMAP.md` or `CHANGELOG.md`.
2. **Never push to upstream**, never force-push, never rewrite pushed history. Push `origin main`
   only after `/verify` passed.
3. **Yomitan's vendored files are never edited by hand**; they change only through the bump script.
4. **GPL-clean builds:** default `local` distribution profile, never `-Pdist=ci|github` or
   `-Pinclude-telemetry` (Firebase). Never bundle dictionaries, audio or OCR models; never use
   Google's closed Lens or ML Kit binaries. Credit borrowed code (licence must be GPL-compatible).
5. **No secrets** read or written (keystores, `google-services.json`, `.env`); no system installs
   (JDK 25 is the JDK; ask the owner if a tool is missing).
6. **No claim without evidence**: say what ran and what it showed; report failures as failures.

## Working well

- **Cost is turns times context.** Start cold (session-start lines, `git status`, the roadmap's
  *Now*), read only what the task needs, hand broad reading to Explore subagents, batch commands.
  One roadmap item per session. GitHub automation pushes to `main`: `git pull --ff-only` first.
- **Builds:** `scripts/fork/gw <tasks>` (short summary, full log in `build/fork-logs/`), one Gradle
  build at a time. Cheapest check first: `:app:compileDebugKotlin`, then touched test classes.
- **Never poll:** long work runs in the background and you wait for its notification.
- **Models:** Opus 5.5 is the floor for every agent (decision D-012); lower effort, not a smaller
  model, for easy steps.
- **Commit each coherent step**: `type(scope): summary` (imperative, lower case, at most 72
  characters), a body that leads with one or two plain sentences for anything non-trivial, no em
  dashes, no bare `#N` (write `Zatsuy/Reikai-JP#N`), no AI credit lines. `scripts/fork/githooks/`
  enforces it; install with `scripts/fork/install-githooks.sh`.
- **Knowledge goes in the repo, not private memory:** owner answers in `decisions.md`, status in
  the roadmap, research in `docs/fork/research/`, harness changes in `docs/fork/harness-log.md`.
  End a session with the tree committed and the roadmap current.

## Map

`app/` the Android app (fork code under `app/src/main/java/jp/reikai/`); `domain/`, `data/`,
`core/`, `source-*` upstream modules; `jp-yomitan/` the Yomitan engine module (vendored Yomitan,
changed only by `scripts/fork/yomitan_bump.py`; stand-in, Anki and audio bridges); `docs/fork/` fork docs; `scripts/fork/` fork tooling;
`../refs/` read-only reference clones (mihon, lnreader, lnreader-plugins, tsundoku, chimahon,
yomihon, hoshidicts, yomitan, ttu-ebook-reader, Hoshi-Reader).
