# Decisions

What the owner has decided, and what is still open. Agents read this before asking the owner
anything: a question already answered here is never asked again. Newest entries go at the top of
the log; *Standing decisions* is the current state.

## Standing decisions

| ID | Decision | Since |
|---|---|---|
| D-001 | **Upstream is Reikai** (unseensnick/Reikai), tracked at its newest development branch (`feat/<version>`, today `feat/0.4.0`) with `git merge`. Mihon arrives through Reikai. | 2026-09-27 |
| D-002 | **Run Yomitan's own code, unmodified**, pinned to a release, with an automated workflow that follows new Yomitan releases. | 2026-09-27 |
| D-003 | **A Japanese reading mode** with the reader's choice of **vertical or horizontal** text. It is the default for Japanese text but never forced: clearly labelled, with a one-tap way back to the standard reader. | 2026-09-27 |
| D-004 | **Licence: GPL-3.0-or-later** for the fork (needed to run Yomitan's code). Public repository, not for sale. Builds stay free of proprietary SDKs (no Firebase). | 2026-09-27 |
| D-005 | **The roadmap is easy to follow** and says, at every step, what the owner has to do. | 2026-09-27 |
| D-006 | **App name "Reikai JP"**, application id `app.reikai.jp` (installs beside upstream Reikai). | 2026-09-27 |
| D-007 | **No AI credit line** (`Co-Authored-By`) in commits or PRs. | 2026-09-27 |
| D-008 | **Performance and smoothness are a pillar.** Agents optimise what they find, when the fix is safe and verified. | 2026-09-27 |
| D-009 | **Agents run the development workflows themselves** and ask the owner only what only the owner can answer or do (AskUserQuestion). When a request does not fit the evidence, agents explain and suggest a better option. | 2026-09-27 |
| D-010 | **Kakuyomu Popular/Latest is a plugin bug** (the site moved to Next.js), not an app bug: not fixed in the app. A ready upstream issue text is in `docs/fork/research/landscape-2026-09.md`. | 2026-09-27 |
| D-011 | **The upstream harness is replaced** by a lean, self-improving one (`docs/fork/harness.md`). | 2026-09-27 |
| D-012 | **Model floor: Opus 5.5** for every agent, no Haiku or Sonnet (inferred from the owner's global settings and their other project; confirm or change here). | 2026-09-27 |
| D-013 | **The fork runs none of upstream's CI pipelines**; it has its own workflows only. | 2026-09-27 |
| D-014 | **JDK 25 locally; agents install nothing.** The owner's JDK 25 builds the project (verified: `:app:compileDebugKotlin` succeeds on Gradle 9.7.1). CI keeps upstream's JDK 21. The owner installed the GitHub CLI (`gh`, logged in as Zatsuy) on 2026-09-27. | 2026-09-27 |
| D-015 | **Automation needs none of the owner's attention.** A hobby project the owner will not tend for long: updates apply themselves when the automated checks pass (upstream merges, Yomitan updates, app releases); what fails stops quietly, pushes nothing, and waits for the owner's next agent session, whose session-start lines report it. No pull requests to review, no weekly chores, nothing to remember. Agents never add a recurring owner task. | 2026-09-27 |
| D-016 | **The owner reads mostly on a tablet**: Galaxy Tab S10 FE (SM-X520, Android 16, 1440x2304). Also a Galaxy A54 phone (SM-A546E, Android 16). Both connect over adb; device checks start on the tablet. The app keeps working on every device Reikai supports. | 2026-09-27 |
| D-017 | **The app updates itself from this fork's GitHub Releases**, through the built-in updater (a "new version" screen with one button, as in Mihon). Releases are signed with one permanent key, made on 2026-09-27 by `scripts/fork/setup-github.sh` and stored as the secrets `SIGNING_KEY`, `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD` (backup in the owner's `~/.local/share/reikai-jp/signing/`). The key is never replaced and agents never read it. | 2026-09-27 |
| D-018 | **No Claude in CI** (was O-002). Automation that cannot finish on its own waits for an agent session the owner starts ("if the automatic updates break something I'll have an agent fix it"). | 2026-09-27 |

## Open

Questions that need the owner. Each has options and a recommendation; the agent that reaches the
item asks with AskUserQuestion and moves the answer to *Standing decisions*.

- **O-001 Novel source language filter: one mechanism or two?** Extensions will share Mihon's
  enabled-languages setting (both content types). Installed *novel sources* still use Reikai's
  separate disabled-languages list. Options: keep the two (no migration), or fold novels into the
  shared setting (a migration). Recommendation: decide when the language-filter item is built,
  after seeing both on the device.

## Log

- **2026-09-27** Owner, on the weekly upstream pull request: "I really don't want to have to
  remember this hobby project every week for the rest of time ... for this kind of project I feel
  like automated workflows should require less work ... Make this a principle, I won't work on
  this project for that long, I want updates to be automatic and have some easy to follow way with
  a guide to update the app in my tablet (making it automatic if possible, Mihon updates are just a
  screen where I press a button) ... if the automatic updates break something I'll have an agent
  fix it if I still use the app." Reads mostly on the tablet; the app must work on any device
  Reikai works on. Approved the Bash guard fix. Installed and logged in `gh`, ran
  `scripts/fork/setup-github.sh` (deploy key verified able to push workflow changes; signing key
  created).
- **2026-09-27** Owner: fork follows upstream's newest branch locally and on GitHub; run Yomitan
  directly with automatic updates; vertical *and* horizontal Japanese modes; keep Reikai as the
  upstream; GPL fine ("not making this product for selling ... no problem with keeping it
  public"); transparent roadmap; add the extension language filter; Japanese mode default for
  Japanese text, not forced, "transparent to the user what it is and how to go back"; performance
  "one big pillar"; Kakuyomu listing is not ours to fix if it is the plugin; agents run workflows
  and ask only what only the owner can answer; replace the upstream harness with a
  self-improving one. App name "Reikai JP"; no AI credit line. Upstream pipelines must not run on
  the fork ("so the project is independent"). "I don't want you to install JDK 21, I already
  have JDK 25 and it works fine."

- **2026-09-26** Research pass and architecture proposal (`docs/fork/architecture.md`,
  `docs/fork/research/landscape-2026-09.md`).
