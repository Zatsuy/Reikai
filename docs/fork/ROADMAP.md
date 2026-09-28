# Reikai JP roadmap

**How to read this.** Agents do everything that is not marked **You:**. To make progress, open
Claude Code in the repository and type `/next`: an agent takes the first open item under *Now*,
builds it, verifies it, and tells you what (if anything) you need to do. Items are done in order
unless a **You:** step is still waiting. Why things are built this way:
[architecture.md](architecture.md); what you decided: [decisions.md](decisions.md).

## Your checklist right now

1. *(When you have five minutes)* Open **Reikai JP** on the tablet (already installed and updated
   by the agent) and go through its **Welcome!** setup: pick a new storage folder, and restore a
   backup from upstream Reikai if you want your library there. Steps:
   [install guide](install.md). On the phone, install it with the same guide if you like.
2. **Set up word lookup** once Reikai JP offers its next update (Phase 3), on the tablet:
   1. Settings → Japanese → **Get recommended dictionaries** → tap Download next to **Jitendex**
      (about 3 minutes; add a frequency or pitch-accent dictionary from the same list if you
      like), then go back.
   2. Under AnkiDroid tap **Allow**, then **Allow** in the system dialog.
   3. Tap **Set up cards for Lapis** and pick the deck your cards should go to.
   4. *(Optional)* **Import your desktop Yomitan settings**: on the computer, Yomitan → Settings →
      Backup → Export Settings, copy the file to the tablet, then pick it there.
   5. *(Optional)* **Local audio file**: copy your `android.db` to the tablet, pick it, wait for the
      copy, then delete the original. If "Speak words that have no recording" says no Japanese
      voice is installed, install one in Android's text-to-speech settings.
   6. Try it: long-press a word in a Japanese chapter → **Look up**.
   On the phone, repeat 1-3 only after AnkiDroid has synced the Lapis note type there.
3. *(Once)* In AnkiDroid → Browse, search `Abdicar` and check the note looks as you left it: an
   agent's stray tap opened it in the editor during a test and backed out without typing. If
   anything changed, fix the field by hand (AnkiDroid's Undo only reaches its latest actions). *(Optional)* AnkiDroid →
   Check media removes the few small audio files left from the test cards.
4. Start a **new** Claude Code conversation and type `/next` (one roadmap item per conversation
   keeps agents fast and cheap).

**What runs without you** (decision D-015): every Monday GitHub merges upstream Reikai's new work
into the fork, checks it, and pushes it; once a day, when the app changed, a new version is
published and Reikai JP offers it on its **New version available!** screen when you next open it;
every Wednesday a new Yomitan release that is a week old and passes the checks is taken in. When something fails, nothing breaks: it just stops, GitHub may
email you (safe to ignore), and the next agent session you start sees it and fixes it.

## Now: Phase 4, Japanese reading mode

- [ ] **4.1 The reader**: vertical or horizontal text (your choice), pages or scrolling, furigana
  modes, Japanese fonts and line breaking, position kept when fonts change.
- [ ] **4.2 Default for Japanese, never forced**: opens automatically for Japanese novels in
  vertical text; the first time, a one-time message offers horizontal instead (D-026); the reader
  menu has an option to return to the standard reader (remembered per novel).
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

## Done: Phase 3, Yomitan engine and the lookup popup (2026-09-28)

Built as one item (D-027); plan and as-built notes in
[research/phase3-design-2026-09.md](research/phase3-design-2026-09.md).

- [x] **3.1 Engine module.** Yomitan 26.9.8.0 vendored unmodified in the fork module `jp-yomitan/`
  at its own private address (`https://yomitan.reikai.invalid`), with a production stand-in and
  tripwire. Starts in 0.92-0.94 s with Jitendex on the tablet (spike 1.4-1.6 s), only while lookup
  is on and a Japanese reader, the dictionary or a lookup needs it; stops 60 s after the last use.
  Dictionary pictures draw; imports work from Yomitan's own settings page (Jitendex 147 s tablet,
  184-197 s phone); chapter pages cannot reach Anki or settings; requests to a vanished page are
  answered. Engine alone: at most about +160 MB on either device (app growth plus a 76-103 MB
  WebView renderer); the budget is +200 MB.
- [x] **3.2 Anki** through AnkiDroid's own provider (no extra app): duplicate check (4-11 ms per
  popup), audio and pictures stored as media, "view note" opens AnkiDroid's browser.
- [x] **3.3 Audio**: Yomitan's online sources, a local `android.db` (copied into the app), the
  device's Japanese voice last.
- [x] **3.4 The popup**: long-press selects whole Japanese words (the one-kanji selection was
  Android selecting from the gap nearest the finger, plus Chromium selecting one character in
  WebView mode); "Look up" first in the selection bar; Yomitan's results in a sheet the app places
  (warm tap to results on the tablet: median ~53 ms, p95 ~75 ms; phone median 84-87 ms); "Look up
  in Reikai JP" in every app; a dictionary search screen.
- [x] **3.5 Settings**: Settings → Japanese (lookup switch, recommended dictionaries, AnkiDroid
  permission, "Set up cards for Lapis", local audio, text-to-speech, desktop import, all Yomitan
  settings); phone-friendly Yomitan defaults on a fresh profile only.
- [x] **3.6 Automatic Yomitan updates**: `fork-yomitan-update.yml` every Wednesday takes a
  release once it is 7 days old, runs a static tripwire and a headless Chrome smoke test, and
  pushes only when both pass (about 100 CI minutes a year).
  **You:** the setup steps in *Your checklist right now*.
  Ruling: the engine's own address, never upstream's `appassets.androidplatform.net` - upstream's
  reader serves chapter images there - cost if wrong: none, nothing to migrate yet.
  Ruling: `android.db` is copied into the app - Android refuses SQLite on the picked file in place
  - cost if wrong: its size again in storage.
  Ruling: the sheet opens at the top when it would cover the word - no reader-scrolling seam
  needed - cost if wrong: the sheet moves between top and bottom.
  Ruling: the Dictionary shortcut goes on the home screen from Settings → Japanese, not in the
  long-press menu - Samsung shows only four and dropped Browse - cost if wrong: one more tap.
  Ruling: Clear WebView data keeps only the engine's IndexedDB - it used to erase every dictionary
  - cost if wrong: none seen.
  Ruling: Yomitan's own vitest suites are not run on updates - they test its source with their own
  mocks, not our stand-in - cost if wrong: a behaviour change with no new API names passes the
  checks (the 7-day soak and the next agent session catch it).
  Open, carried on: in WebView mode a selection can include ruby furigana (4.3's taps in the
  Japanese reader replace this path); Yomitan's audio auto-play does not play in the sheet; the
  renderer (~90 MB) stays until the app restarts after lookup is switched off; a picture on a card
  and AnkiDroid sync are not yet proven on a device; the phone was not re-checked after the final
  fixes.
  **Done 2026-09-28** in `48293af25`..`1cf0fa426` (3.1), `8808a2002`..`d966a4e75` (3.2, 3.3),
  `44ea8c179`..`63da3ac04` (3.4), `4157cd648`..`257e8e75f` (3.5), `00f2ddb01`..`e1490630e` (3.6),
  review and device fixes `a7f9a525b`..`b6acc712a` and `bfd79c7ee`..`b4d3ac922`. Agent's device
  use (D-023): "stay awake while charging" was on for the tablet and the phone during the checks
  and is switched back off afterwards. The phone's debug app was uninstalled; the tablet's debug
  app keeps Jitendex and a Kakuyomu novel for later checks.

## Done: Phase 2, Yomitan spike: go (2026-09-27)

- [x] **2.1 Prove Yomitan runs inside the app, on your tablet and phone.** Yomitan 26.9.8.0 ran
  unmodified in a debug-only spike screen on both devices, with Jitendex, JPDB frequencies and
  Kanjium pitch accents. A tapped word in vertical text shows Yomitan's own popup in 88-108 ms on
  the tablet and 64-99 ms on the phone (p50-p95, target 150-200); Jitendex imports in 2.4 and 3.5
  min; a Lapis card went into AnkiDroid from Yomitan's own add button, filled by Yomitan's
  templates (word, furigana, definition, the sentence with the word in bold, pitch, frequency).
  Missed or open: the engine starts in 1.4-2.5 s (target 1 s), the first popup of a session takes
  0.3-0.8 s, reading with a popup costs about +350 MB (tablet) / +230 MB (phone), dictionary
  pictures do not draw, Yomitan's own import screen hangs (the spike imports from a page), and
  Yomitan's popup does not fit a phone in vertical text. All of it is carried into Phase 3 below.
  Findings: [research/yomitan-spike-2026-09.md](research/yomitan-spike-2026-09.md).
  **You:** decided go (D-024); nothing else was needed: AnkiDroid was already installed and the
  agent downloaded the dictionaries itself.
  Ruling: Jitendex in place of plain JMdict - it is JMdict plus more and what Yomitan recommends -
  cost if wrong: none, the import path is the same.
  Ruling: the Anki card on the tablet only - creating Lapis on both devices before they sync would
  give you two note types named Lapis - cost if wrong: the phone's AnkiDroid is checked in 3.2.
  Ruling: import from a page instead of Yomitan's worker - Android WebView never serves a worker
  started by a worker - cost if wrong: a stand-in change in 3.1.
  **Done 2026-09-27** in `b18c76007`, `4ccf77481`, `a173550f6`, `5340c22cc`. Agent's device use
  (D-023): "stay awake while charging" was on for both devices during the tests and switched back
  off afterwards. The test card and deck were deleted; Lapis 1.7.0 stays in your AnkiDroid
  collection (your next AnkiDroid sync uploads it). Your choice afterwards: the spike's test data
  was removed from both devices (about 400 MB each) and the developer app uninstalled from the
  phone; the spike's code stays in the repository for Phase 3.

## Done: Phase 1, quick wins and groundwork (2026-09-27)

- [x] **1.1 Reikai JP on your tablet, updating itself.** The app gets its own name and id
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
  **Done 2026-09-27** in `eed0c4f28`, `24c809c10`, `a6b7c4c83`, `e70101419` (tests retried once:
  see 1.6). Releases r2665 and r2668 published by the workflow; the agent installed r2665 on the
  tablet over adb, the app offered r2668 on its update screen and installed it through Download,
  the one-time install permission and Update (`versionName 0.3.2-2665` to `0.3.2-2668`). The
  owner's first-install step was done by the agent; the Welcome setup is left to the owner.
- [x] **1.2 Language filter for novel extensions** (your report). The extension list gets a
  language filter for novels, sharing one setting with manga extensions; installing a plugin turns
  its language on so it never disappears. *Done when:* choosing only 日本語 shows only Japanese
  extensions. **You:** answer one question when asked (decision O-001).
  Ruling: installed, updating and failed extensions stay listed whatever the filter says, as in
  Mihon - they are what "never disappears" protects - cost if wrong: one condition in
  `jp.reikai.browse.ExtensionLanguages.offered`.
  Ruling: turn on the languages of already-installed novel extensions once, instead of on every
  install - an install can only start from a row whose language already shows - cost if wrong: a
  hook in `LnPluginManagerViewModel.install`.
  **Done 2026-09-27** in `58e8fcf29`, `849ac0e16`. Owner chose to keep the Sources screen's novel
  switches separate (D-020). On the tablet (debug build): the Filter action shows on the Novels
  chip and lists the plugin languages; only 日本語 on left exactly kakuyomu and Syosetu; with only
  English on, the installed Syosetu stayed under Installed; with the seed flag reset, a relaunch
  switched 日本語 back on and kakuyomu was offered again. IReader's own codes (`jp`, `tu`, `in`,
  `cn`, `multi`) are covered by a unit test only (no IReader repo on the tablet).
- [x] **1.3 Performance baseline.** Repeatable measurements on your tablet and phone (cold start,
  library open, novel chapter open, memory), saved so every later change is compared against real
  numbers. **You:** devices connected (done).
  Ruling: measure upstream's `benchmark` build type (R8, profileable, `app.reikai.jp.benchmark`) -
  release-like and its own data, so the owner's library is never touched - cost if wrong: numbers
  differ from the signed nightly only by signing.
  Ruling: a generated offline library (300 local manga, 30 novels with downloaded chapters) instead
  of the owner's - identical on both devices, no network - cost if wrong: sizes are a guess;
  `FIXTURE_INFO` in `scripts/fork/perf.py` versions it.
  Ruling: an adb script over Macrobenchmark - fork-owned, no seams in upstream's
  `baseline-profile` module, runs from the agent's shell - cost if wrong: less precise than
  Perfetto traces for sub-frame work.
  Ruling: library open is the manga chip at a cold start; chapter open is the native renderer -
  the larger list and the default renderer - cost if wrong: the novel chip and WebView mode need
  their own marks (noted in the README).
  **Done 2026-09-27** in `e5cf25747`, `3da7f326f`, `6888aea28`. Baseline saved for both devices
  at `3da7f326f` ([perf/README.md](perf/README.md)), repeated twice (once each device alone, once
  side by side) to set the noise band: tablet cold start 739 ms, library on screen 531 ms, chapter
  open 215 ms, 181/370 MB (library/reader); phone 954, 753, 236 ms, 146/286 MB. Findings: cold
  start rests on the 500 ms splash floor, the novel reader doubles memory. Your phone now has
  Reikai JP r2668 set up on `/sdcard/Reikai` (empty library, same permissions as the tablet).
- [x] **1.4 Japanese sites in the plugin host.** Novel plugins now read pages in Shift_JIS,
  EUC-JP and other non-UTF-8 charsets, including sites that name the charset only inside the page
  (Aozora Bunko used to arrive garbled). The "one lock" half was already fixed upstream before the
  fork began (`93d8a0425` gives each plugin its own engine, and global search runs 5 sources at a
  time), so global search does not get faster from this item. The fix stays in Reikai JP: you chose
  not to offer it upstream (D-022; ready in [upstream-prs](upstream-prs/README.md) if that changes).
  Ruling: no lock change - already fixed upstream; only two calls to the same plugin still queue,
  and removing that needs a second engine per plugin (memory, D-008) - cost if wrong: that engine
  later.
  Ruling: pick the charset as a browser does (label, header, `<meta>`, UTF-8) with WHATWG labels
  (Shift_JIS means windows-31j) - what lnreader's WebView does - cost if wrong: one table in
  `jp.reikai.novel.host.LnBodyDecoder`.
  Ruling: `TextDecoder` with a non-UTF-8 label keeps a base64 hop through JS - no LNReader plugin
  uses one today - cost if wrong: pass the bytes natively (a device check of dokar's mapping).
  **Done 2026-09-27** in `aec4595c8`, `63f538ed2`, `1289fa879` (review fixes: the whole head is
  scanned, XML declarations read, EUC-JP's ①, no raw copy for `fetchText`). JVM tests
  `LnBodyDecoderTest`, `LnHostBridgeCharsetTest` (a mutation check fails 4 of them); on the tablet
  `LnCharsetDeviceTest` 6/6 in the real QuickJS host (Shift_JIS and EUC-JP `TextDecoder` with ①,
  the live Aozora page, the encoding argument, raw bytes for `arrayBuffer`), and upstream's
  plugin sweep `HeadlessJsIntegrationTest` 7/7 (35 live plugins loaded, 6 full chains); the same
  6/6 and 7/7 on the phone. Upstream-style branch `pr/plugin-charsets` on the fork, not offered.
- [x] **1.5 Kakuyomu Popular/Latest.** A plugin bug, not an app bug (D-010): the site's ranking
  pages moved to Next.js. The agent wrote a fix for the plugin's own project (Popular read from the
  page's data, Latest added, version 1.1.0; its checker passes) and tried it in the debug app on
  the tablet through a local test repository: Popular, filters, paging, Latest, search and a
  chapter all worked. Nothing changes in Reikai JP itself, and you chose not to offer the fix to
  LNReader (D-022), so Kakuyomu's Popular and Latest stay empty until someone fixes the plugin
  upstream; the tested patch is kept in [upstream-prs](upstream-prs/README.md).
  **Done 2026-09-27** in `63f538ed2` (the patch and its pull request text).
- [x] **1.6 Steady automated checks** (agents only). Three upstream tests failed at random on
  GitHub. The tests were at fault, not the app: one deleted a download before the screen was
  listening for it, one left background work running into the next test, and one (found by the
  first CI run without the retry, `RelatedMangasBrowseViewModelTest`) changed the recommendation
  list before the screen had shown a title as added. All three are fixed as test seams, and GitHub
  no longer retries failed tests. Owner (2026-09-27): no 20 repeated CI runs
  on the free plan, so the proof is local (D-021): a harness that forces the bad timing made the
  tests fail 20/20, 10/10 and 20/20 before the fixes and 0 after, 30 plain runs of each class
  passed, and removing the feed fix makes it fail again 5/5. Sweeps of all 3,683 unit tests under
  the forcings that caught these found no other. The full unit suite passes locally without the
  retry. **Done 2026-09-27** in `f9313a161`, `1289fa879`, `49fbd9527`.
  Fork CI then passed without the retry (run 36350606321). Upstream-style branch
  `pr/recents-test-flakes` on the fork, not offered (D-022).

## Done: Phase 0, foundation (2026-09-27)

- [x] Fork follows upstream's newest branch, locally and on GitHub (`668d48c34`).
- [x] Upstream's CI pipelines removed from the fork (`8d310012a`).
- [x] Research, architecture, decisions and this roadmap.
- [x] New harness: `AGENTS.md`, skills, hooks, rules, fork scripts, self-measurement.
- [x] Licence: GPL-3.0-or-later, upstream's Apache notices kept.
- [x] Fork CI and the automatic upstream sync (every Monday, pushes when its checks pass).
- [x] You: GitHub CLI installed and logged in; one-time GitHub setup run (sync key, app signing
  key); tablet and phone connected over adb.
