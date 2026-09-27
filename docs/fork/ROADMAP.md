# Reikai JP roadmap

**How to read this.** Agents do everything that is not marked **You:**. To make progress, open
Claude Code in the repository and type `/next`: an agent takes the first open item under *Now*,
builds it, verifies it, and tells you what (if anything) you need to do. Items are done in order
unless a **You:** step is still waiting. Why things are built this way:
[architecture.md](architecture.md); what you decided: [decisions.md](decisions.md).

## Your checklist right now

Nothing is waiting on you. Start a **new** Claude Code conversation and type `/next` (one roadmap
item per conversation keeps agents fast and cheap).

**What runs without you** (decision D-015): every Monday GitHub merges upstream Reikai's new work
into the fork, checks it, and pushes it; once item 1.1 lands, new versions of the app reach your
tablet through its update screen. When something fails, nothing breaks: it just stops, GitHub may
email you (safe to ignore), and the next agent session you start sees it and fixes it.

## Now: Phase 1, quick wins and groundwork

- [ ] **1.1 Reikai JP on your tablet, updating itself.** The app gets its own name and id
  (`app.reikai.jp`; agents' debug builds `app.reikai.jp.dev`) so it installs beside upstream Reikai,
  and About links here. A release workflow builds a signed app and publishes it on this fork's
  GitHub Releases whenever `main` has app changes (checked daily, so at most one update a day), and
  the built-in updater checks this fork: a "new version" screen with one button, as in Mihon.
  Includes a one-page install-and-update guide. *Done when:* the app on your tablet offers a new
  release and installs it. **You:** install the first release once, following the guide (Android
  asks you to allow installs from your browser once).
  *For the agent:* the build already signs from `REIKAI_GITHUB_RELEASE` plus
  `storeFile`/`storePassword`/`keyAlias`/`keyPassword` (`app/build.gradle.kts`), and the secrets
  exist (D-017). The updater needs `-Penable-updater` (the default `local` profile turns it off)
  and a seam at `GITHUB_REPO` (`AppUpdateChecker.kt`). The preview comparison fits: tags
  `r<commit count>` (upstream's deleted `nightly.yml` is a starting point:
  `git show 8d310012a^:.github/workflows/nightly.yml`). Needs `permissions: contents: write`, a
  skip when no app files changed since the last release, pruning old releases, and the same
  keepalive job as `fork-upstream-sync.yml`. The session-start hook already watches
  `fork-release.yml`.
  Ruling: publish upstream's `nightly` build type with its `.debug` suffix removed - it already
  compares `r<count>` tags and shows "Nightly r<count>" in About, so five one-line seams suffice -
  cost if wrong: About says "Nightly" and a few dev-only options show, as in upstream's nightlies.
  Ruling: keep upstream's Website link in About, point GitHub at this fork - the site documents
  features the fork keeps - cost if wrong: one line.
- [ ] **1.2 Language filter for novel extensions** (your report). The extension list gets a
  language filter for novels, sharing one setting with manga extensions; installing a plugin turns
  its language on so it never disappears. *Done when:* choosing only 日本語 shows only Japanese
  extensions. **You:** answer one question when asked (decision O-001).
- [ ] **1.3 Performance baseline.** Repeatable measurements on your tablet and phone (cold start,
  library open, novel chapter open, memory), saved so every later change is compared against real
  numbers. **You:** devices connected.
- [ ] **1.4 Japanese sites in the plugin host.** Shift-JIS and EUC-JP pages decode correctly, and
  plugin calls stop waiting behind one lock (faster global search). These are general fixes, so
  they are also prepared as a pull request for upstream Reikai. **You (optional):** open that pull
  request on GitHub; the agent prepares the branch and text.
- [ ] **1.5 Kakuyomu Popular/Latest.** A plugin bug, not an app bug (the site changed its page);
  the issue text is ready in [research](research/landscape-2026-09.md). **You (optional):** file it
  at `LNReader/lnreader-plugins`, or tell an agent to prepare a fix pull request for that repo.

## Next: Phase 2, Yomitan spike (go or no-go)

- [ ] **2.1 Prove Yomitan runs inside the app, on your tablet and phone.** A throwaway build that imports
  JMdict plus a frequency and a pitch dictionary, measures lookup speed, looks up a word tapped in
  vertical text, and adds a Lapis card to AnkiDroid. Targets: popup in under about 150-200 ms,
  import without crashing. **You:** install AnkiDroid, download the dictionaries the agent links
  (they are free but not ours to ship), keep the devices connected, then decide go or no-go with the
  numbers. If no-go, agents present the fallbacks in [architecture.md](architecture.md).

## Then: Phase 3, Yomitan engine and the lookup popup

- [ ] **3.1 Engine module**: pinned Yomitan, the browser-extension stand-in, its tripwire, start
  only when needed.
- [ ] **3.2 Anki**: add cards through AnkiDroid (duplicate check, audio, pictures).
  **You:** grant the AnkiDroid permission when the app asks; pick your note type (Lapis
  recommended).
- [ ] **3.3 Audio**: online sources, the local `android.db` audio collection, phone TTS as backup.
  **You (optional):** copy a local audio collection to the tablet.
- [ ] **3.4 The popup**: Yomitan's results in a phone-friendly sheet; a dictionary search screen;
  "Look up in Reikai JP" from any app's text-selection menu.
- [ ] **3.5 Settings**: a simple Japanese section plus Yomitan's full settings; import your desktop
  Yomitan backup. **You (optional):** export your desktop Yomitan settings and dictionaries.
- [ ] **3.6 Automatic Yomitan updates**: a weekly check vendors a new Yomitan release, runs its
  tests through our stand-in, and applies it by itself when they pass (a failure waits for the
  next agent session, D-015).

## Then: Phase 4, Japanese reading mode

- [ ] **4.1 The reader**: vertical or horizontal text (your choice), pages or scrolling, furigana
  modes, Japanese fonts and line breaking, position kept when fonts change.
- [ ] **4.2 Default for Japanese, never forced**: opens automatically for Japanese novels, explains
  itself the first time, and one tap in the reader menu returns to the standard reader (remembered
  per novel).
- [ ] **4.3 Tap to look up** in the reader, with the sentence, highlight, book title, chapter and
  cover filled into the card.
- [ ] **4.4 Character counts and reading statistics**, compatible with ttu.
- [ ] **4.5 Tsundoku parity**: list what Tsundoku's reader has that 0.4.0 lacks, then build the
  ones you want. **You:** pick from the list.

## Later

- **Phase 5, local books**: import EPUB and TXT files as novels; they get the Japanese mode, lookup
  and statistics too.
- **Phase 6, learning extras**: a mining log with a jump back to the passage; colouring of known,
  unknown and frequent words from your Anki cards; sentences with exactly one unknown word; TTS
  sentence audio for cards.
- **Phase 7, manga lookup**: tap text on manga pages: first `.mokuro` files, then a region you draw
  read on the phone (model downloaded on request), then automatic text detection.

## Ideas, not scheduled

- ttu-compatible progress sync; audiobook read-along; machine translation of sentences.

## Recurring

- **Upstream syncs**: automatic every Monday (`fork-upstream-sync.yml`); an agent runs
  `/sync-upstream` only when the session-start lines report a failed run. Log:
  - 2026-09-27: fork moved onto `upstream/feat/0.4.0` at `668d48c34`.
- **Retro** (`/retro`, after a large item or when you ask): see [harness.md](harness.md).

## Done: Phase 0, foundation (2026-09-27)

- [x] Fork follows upstream's newest branch, locally and on GitHub (`668d48c34`).
- [x] Upstream's CI pipelines removed from the fork (`8d310012a`).
- [x] Research, architecture, decisions and this roadmap.
- [x] New harness: `AGENTS.md`, skills, hooks, rules, fork scripts, self-measurement.
- [x] Licence: GPL-3.0-or-later, upstream's Apache notices kept.
- [x] Fork CI and the automatic upstream sync (every Monday, pushes when its checks pass).
- [x] You: GitHub CLI installed and logged in; one-time GitHub setup run (sync key, app signing
  key); tablet and phone connected over adb.
