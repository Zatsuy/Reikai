# Reikai JP roadmap

**How to read this.** Agents do everything that is not marked **You:**. To make progress, open
Claude Code in the repository and type `/next`: an agent takes the first open item under *Now*,
builds it, verifies it, and tells you what (if anything) you need to do. Items are done in order
unless a **You:** step is still waiting. Why things are built this way:
[architecture.md](architecture.md); what you decided: [decisions.md](decisions.md).

## Your checklist right now

1. **Set up word lookup** (Reikai JP r2818, on both devices since 2026-09-28, has it), on the tablet:
   1. Settings → Japanese → **Get recommended dictionaries** → tap Download next to **Jitendex**
      (about 3 minutes; **JPDBv2** further down the same list adds word frequencies), then go back.
   2. Under AnkiDroid tap **Allow**, then **Allow** in the system dialog.
   3. Tap **Set up cards for Lapis** and pick the deck your cards should go to.
   4. *(Optional)* **Import your desktop Yomitan settings**: on the computer, Yomitan → Settings →
      Backup → Export Settings, copy the file to the tablet, then pick it there.
   5. *(Optional)* **Local audio file**: copy your `android.db` to the tablet, pick it, wait for the
      copy, then delete the original. If "Speak words that have no recording" says no Japanese
      voice is installed, install one in Android's text-to-speech settings.
   6. Try it: open a Japanese novel (it opens in the new Japanese reader) and tap a word.
   On the phone, repeat 1-3 only after AnkiDroid has synced the Lapis note type there.
2. *(Once)* In AnkiDroid → Browse, search `Abdicar` and check the note looks as you left it: an
   agent's stray tap opened it in the editor during a test and backed out without typing. If
   anything changed, fix the field by hand (AnkiDroid's Undo only reaches its latest actions). *(Optional)* AnkiDroid →
   Check media removes the few small audio files left from the test cards.
3. *(Optional, two minutes, on the computer)* Give the repository page its preview picture, the one
   shown when the link is shared: GitHub → Zatsuy/Reikai-JP → **Settings** → **General** → **Social
   preview** → **Edit** → **Upload an image**, and pick `docs/fork/media/social-preview.jpg` from
   this folder. GitHub offers no way for an agent to do it.
4. Start a **new** Claude Code conversation and type `/next` (one roadmap item per conversation
   keeps agents fast and cheap).

**What runs without you** (decision D-015): every Monday GitHub merges upstream Reikai's new work
into the fork, checks it, and pushes it; once a day, when the app changed, a new version is
published and Reikai JP offers it on its **New version available!** screen when you next open it;
every Wednesday a new Yomitan release that is a week old and passes the checks is taken in. When something fails, nothing breaks: it just stops, GitHub may
email you (safe to ignore), and the next agent session you start sees it and fixes it.

## Now: Phase 5, first run and local books

Two sessions, one item each (D-033); what goes in each phase: D-032.

- [ ] **5.1 First-run Japanese setup**: a native *Set up Japanese* screen instead of Yomitan's
  desktop-style settings page, offered at the end of the Welcome setup and the first time a
  Japanese novel opens with no dictionary, and always in Settings → Japanese. *Done when:* on a fresh
  install a new user gets from nothing to a word lookup with an Anki button on that one screen:
  Jitendex (and, if ticked, the JPDB frequency list) downloads with progress shown in the app,
  AnkiDroid is allowed and a deck picked for Lapis, and Japanese sources show in Browse without
  touching the language filter (today a fresh install on an English phone hides them).
- [ ] **5.2 Local books**: import EPUB and TXT files as novels (one more source behind upstream's
  novel source seam). *Done when:* an EPUB with furigana, pictures and a cover, and a plain TXT
  file, picked from storage, appear in the library, open in the Japanese reader with lookup,
  statistics and translation, and a Japanese EPUB opens in the direction its book says.

## Later

- **Phase 6, manga lookup** (you chose it before the learning extras, and its design, D-034), two
  sessions: 6.1 measure reading a page on the tablet and phone, then tap a word on a manga page and
  get the same popup as in novels: the page's text lines are found and read on the device when the
  page is shown, by a manga-trained model downloaded on request (about 12 MB, never bundled,
  deletable), no cloud; 6.2 pages ahead read in the background, the webtoon viewer, and a
  long-press-and-drag box for text the detector missed (sound effects, handwriting).
  *Done when:* on a page already read, tap to popup is inside the novel budget (p95 under 200 ms),
  in the paged and webtoon viewers on the tablet and the phone, with the reading's battery cost
  measured. Research: [research/manga-ocr-2026-09.md](research/manga-ocr-2026-09.md).
- **Phase 7, learning extras**, three sessions: 7.1 a mining log with a jump back to the passage;
  7.2 known, unknown and frequent words coloured from your Anki cards, and sentences with exactly
  one unknown word (its own session: every chapter is split into words, so it gets a speed budget
  before it is built); 7.3 the device's voice reading the card's sentence into Anki.

## Ideas, not scheduled

- Translating a single sentence from the lookup popup (whole chapters can be translated since
  Phase 4); an Aozora Bunko source (public-domain classics with their furigana); `.mokuro` files
  for manga pre-read on a PC (half a session on top of Phase 6).
- Dropped (you, 2026-09-28): ttu-compatible progress sync, audiobook read-along.

## Recurring

- **Upstream syncs**: automatic every Monday (`fork-upstream-sync.yml`); an agent runs
  `/sync-upstream` only when the session-start lines report a failed run. Log:
  - 2026-09-27: fork moved onto `upstream/feat/0.4.0` at `668d48c34`.
- **Retro** (`/retro`, after a large item or when you ask): see [harness.md](harness.md).

## Done: presentation and clean-up (2026-09-28)

- [x] **The repository presents the project.** Renamed `Zatsuy/Reikai` → `Zatsuy/Reikai-JP` (GitHub
  redirects the old name, so apps already installed keep updating; the app, hooks and scripts use
  the new one). A README written for readers (`.github/README.md`, which GitHub shows first) with a
  header picture, framed screenshots and two clips from the tablet and phone (`docs/fork/media/`,
  3.8 MB, rebuilt from new captures by `scripts/fork/readme_media.py`); a fork `CONTRIBUTING.md`; a
  new description, 18 topics and the download link on the repository page; Wiki and Projects off.
- [x] **Both devices on the newest Reikai JP.** Fork CI had failed on formatting since the Phase 4
  push, and a failure there also stops the daily release, so r2694 (without Phases 3 and 4) was
  still the newest: fixed, released as r2818 and installed over the owner's app on both devices,
  library kept.
- [x] **Leave nothing behind** (D-031): removed the developer, benchmark and test apps and the test
  folders (62 MB each) from both devices; on this computer Gradle's build cache (12 GB), an unused
  Gradle version, local nightly and benchmark outputs (3 GB) and old scratch folders (650 MB); on
  GitHub 55 superseded Actions caches (7.7 GB). `gw`, the session-start hook and the skills keep it
  that way.
  **You:** the social-preview upload in *Your checklist* (optional).
  Ruling: the screenshots show a Kakuyomu web novel, credited under the README - no public-domain
  text is reachable through the installed sources and local books are 5.1 - cost if wrong: a
  re-shoot with `readme_media.py`.
  Open: the reference clones in `../refs` could shrink by about 200 MB as shallow clones (the
  command was refused as irreversible; say "make the refs shallow" to approve it).
  **Done 2026-09-28** in `925d69fa6`..`248047af7`. Agent's device use (D-023): "stay awake while
  charging" was on for both devices and is back off; demo mode was tried and is off; no debug build
  is left on either device, so the next device check installs one (and downloads Jitendex again).

## Done: Phase 4, Japanese reading mode (2026-09-28)

Built as one item in one session (D-028); plan, page contract, rulings and as-built notes in
[research/phase4-design-2026-09.md](research/phase4-design-2026-09.md).

- [x] **4.1 The reader**: a new Japanese reader for novels, vertical or horizontal text, pages or
  continuous scrolling in both directions (your request), ttu's furigana modes, Mincho or Gothic
  or an added font, Japanese line breaking, upright short numbers, sesame emphasis dots; the place
  is kept to the character through size, font, direction and rotation changes. Tablet: a chapter
  step takes a median ~98 ms (upstream's WebView mode ~194 ms), a page turn under 1 ms.
- [x] **4.2 Default for Japanese, never forced**: Japanese sources open in it, vertical, with a
  one-time offer of horizontal (D-026); "Switch to standard reader" / "Switch to Japanese reader"
  in the reader's menu, remembered per novel.
- [x] **4.3 Tap to look up** (D-029): a tap anywhere in a word or on its furigana looks up the whole
  word with Yomitan's own scanner in the page (tablet: tap to results median 120 ms, max 166 ms);
  the card gets the sentence with the word in bold, the chapter and novel, and the cover. "Tap on
  text → Turn pages" makes taps turn pages instead.
- [x] **4.4 Character counts and reading statistics**, counted as ttu counts them: Settings →
  Japanese → Reading statistics, and "Export for ttu".
- [x] **4.5 Tsundoku parity** (D-030, you picked): a status bar (clock, battery, chapter, progress,
  characters and speed), chapters that fit on one screen marked read, chapter translation
  (Google, DeepL or an AI service; keys kept out of backups), EPUB export of downloaded chapters.
  **You:** only if you ran "Set up cards for Lapis" before, run it once more for the cover (in *Your
  checklist*).
  Rulings: in the design doc (16, from "its own viewport, one chapter per document" to "a Japanese
  EPUB is vertical"), plus: volume keys turn pages only with the reader's **Volume keys** switch on,
  as in the standard reader - cost if wrong: one switch.
  Open, carried on: not checked on the phone; the ttu export not yet imported into ttu itself;
  DeepL and AI translation checked only with bad keys; the standard reader's see-through status
  bar and an EPUB with pictures not seen on a device; the "Look up" speed of a tap (median 120 ms)
  is slower than the long-press path's 53 ms, still inside the budget.
  **Done 2026-09-28** in `5b55bd797`..`8a06cf53d`: the reader `4af1df2ad`..`8da0d83bb`, device
  fixes `5bfa3b268`..`5e7d9f819`, lookup `066d39a4c`..`dd706912d`, statistics, status bar and short
  chapters `6981583e3`..`1c09d107d`, translation `c8ec63be5`..`b875a625c`, EPUB `006c1afa8`..`6d34d6734`,
  review fixes `312ac0e7b`..`8a06cf53d`. Agent's device use (D-023): the tablet's "stay awake while
  charging", left on by the previous session, was used and switched **off** at the end of this
  session; its debug app keeps Jitendex and a Kakuyomu novel for later checks. The phone was not
  used.

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
  review and device fixes `a7f9a525b`..`b6acc712a`, `bfd79c7ee`..`b4d3ac922` and `41eb1a5be`..`d85c1aed9`. Agent's device
  use (D-023): "stay awake while charging" was on for the tablet and the phone during the checks;
  the phone's is back off. The tablet's is left **on** at the owner's request (2026-09-28, away
  from home and wanting the tablet awake for the next session): the next agent that uses the
  tablet switches it off (`settings put global stay_on_while_plugged_in 0`) when it is done. The phone's debug app was uninstalled; the tablet's debug
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
