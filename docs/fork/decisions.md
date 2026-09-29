# Decisions

What the owner has decided, and what is still open. Agents read this before asking the owner
anything: a question already answered here is never asked again. Newest entries go at the top of
the log; *Standing decisions* is the current state.

## Standing decisions

| ID | Decision | Since |
|---|---|---|
| D-001 | **Upstream is Reikai** (unseensnick/Reikai), tracked at its newest development branch (`feat/<version>`, today `feat/0.4.0`) with `git merge`. Feature branches are short-lived, so every session start re-picks the branch (`scripts/fork/sync_upstream.py`) and warns when upstream's work seems to have moved elsewhere (owner, 2026-09-27). Mihon arrives through Reikai. | 2026-09-27 |
| D-002 | **Run Yomitan's own code, unmodified**, pinned to a release, with an automated workflow that follows new Yomitan releases. | 2026-09-27 |
| D-003 | **A Japanese reading mode** with the reader's choice of **vertical or horizontal** text. It is the default for Japanese text but never forced: clearly labelled, with a one-tap way back to the standard reader. | 2026-09-27 |
| D-004 | **Licence: GPL-3.0-or-later** for the fork (needed to run Yomitan's code). Public repository, not for sale. Builds stay free of proprietary SDKs (no Firebase). | 2026-09-27 |
| D-005 | **The roadmap is easy to follow** and says, at every step, what the owner has to do. | 2026-09-27 |
| D-006 | **App name "Reikai JP"**, application id `app.reikai.jp` (installs beside upstream Reikai). | 2026-09-27 |
| D-007 | **No AI credit line** (`Co-Authored-By`) in commits or PRs. | 2026-09-27 |
| D-008 | **Performance and smoothness are a pillar.** Agents optimise what they find, when the fix is safe and verified. | 2026-09-27 |
| D-009 | **Agents run the development workflows themselves** and ask the owner only what only the owner can answer or do (AskUserQuestion). When a request does not fit the evidence, agents explain and suggest a better option. | 2026-09-27 |
| D-010 | **Kakuyomu Popular/Latest is a plugin bug** (the site moved to Next.js), not an app bug: not fixed in the app. A tested plugin fix and its pull request text are in `docs/fork/upstream-prs/README.md`. | 2026-09-27 |
| D-011 | **The upstream harness is replaced** by a lean, self-improving one (`docs/fork/harness.md`). | 2026-09-27 |
| D-012 | **Model floor: Opus 5.5** for every agent, no Haiku or Sonnet (inferred from the owner's global settings and their other project; confirm or change here). | 2026-09-27 |
| D-013 | **The fork runs none of upstream's CI pipelines**; it has its own workflows only. | 2026-09-27 |
| D-014 | **JDK 25 locally; agents install nothing.** The owner's JDK 25 builds the project (verified: `:app:compileDebugKotlin` succeeds on Gradle 9.7.1). CI keeps upstream's JDK 21. The owner installed the GitHub CLI (`gh`, logged in as Zatsuy) on 2026-09-27. | 2026-09-27 |
| D-015 | **Automation needs none of the owner's attention.** A hobby project the owner will not tend for long: updates apply themselves when the automated checks pass (upstream merges, Yomitan updates, app releases); what fails stops quietly, pushes nothing, and waits for the owner's next agent session, whose session-start lines report it. No pull requests to review, no weekly chores, nothing to remember. Agents never add a recurring owner task. | 2026-09-27 |
| D-016 | **The owner reads mostly on a tablet**: Galaxy Tab S10 FE (SM-X520, Android 16, 1440x2304). Also a Galaxy A54 phone (SM-A546E, Android 16). Both connect over adb; device checks start on the tablet. The app keeps working on every device Reikai supports. | 2026-09-27 |
| D-017 | **The app updates itself from this fork's GitHub Releases**, through the built-in updater (a "new version" screen with one button, as in Mihon). Releases are signed with one permanent key, made on 2026-09-27 by `scripts/fork/setup-github.sh` and stored as the secrets `SIGNING_KEY`, `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD` (backup in the owner's `~/.local/share/reikai-jp/signing/`). The key is never replaced and agents never read it. | 2026-09-27 |
| D-018 | **No Claude in CI** (was O-002). Automation that cannot finish on its own waits for an agent session the owner starts ("if the automatic updates break something I'll have an agent fix it"). | 2026-09-27 |
| D-019 | **Devices are connected only when needed.** The tablet and phone are not always on or plugged in. When a check needs one and `adb devices` does not list it, the agent asks the owner with AskUserQuestion, naming the device, and continues once they connect it; it does not skip the check or swap it for written test steps unless the owner says so. | 2026-09-27 |
| D-020 | **One language filter for manga and novel extensions; installed novel sources keep their own** (was O-001). Browse → Extensions filters manga extensions, novel extension apps and LNReader plugins by Mihon's enabled languages. Browse → Sources → Filter → Novels keeps Reikai's separate per-language switches for installed novels, so nothing installed vanishes and no migration runs (owner, 2026-09-27). | 2026-09-27 |
| D-022 | **The Phase 1 fixes are not offered upstream** (was a Phase 1 question): the charset decoding and the three test fixes for upstream Reikai and the Kakuyomu fix for LNReader stay in the fork; their branches and texts are kept in `docs/fork/upstream-prs/` (owner, 2026-09-27). | 2026-09-27 |
| D-021 | **GitHub Actions minutes are scarce (free plan).** Agents never spend CI runs to prove something they can prove locally: a flaky test is fixed when a local harness that forces the bad timing fails before the fix and passes after, not after repeated CI runs (owner, 2026-09-27). | 2026-09-27 |
| D-023 | **A connected device is the owner's go-ahead to use it.** When the owner connects the tablet or phone, the agent may use it for the whole session and temporarily change the settings it needs (above all keeping the screen awake while plugged in). It tells the owner straight away when it changes a setting, puts every setting back as it was once it is done with that device for the session, says so at that moment, and repeats it in the final report (owner, 2026-09-27). | 2026-09-27 |
| D-024 | **Go: the lookup engine is Yomitan's own code** (was the Phase 2 go/no-go). The spike ran Yomitan 26.9.8.0 unmodified on both devices within the popup budget; Phase 3 builds the engine on it and must fix what the spike found (dictionary pictures, the import path, engine start and first popup, chapter pages kept off the engine's origin, a popup placed by the app on phones). Details: `docs/fork/research/yomitan-spike-2026-09.md` (owner, 2026-09-27). | 2026-09-27 |
| D-025 | **Yomitan lookup can be switched off, and is on by default.** A reader who does not want the popup (a native speaker, say) turns it off in settings; while it is off the engine never starts, so it costs no memory (owner, 2026-09-27). | 2026-09-27 |
| D-026 | **The Japanese reader starts in vertical text and, once, offers horizontal** (refines D-003). The first time it opens, a short one-time message says it is reading vertically like a printed book and offers to switch to horizontal; the choice stays changeable in the reader's own settings. Going back to the standard (non-Japanese) reader is only an option in the reader menu, not part of that first-time message (owner, 2026-09-27). | 2026-09-27 |
| D-027 | **Phase 3 is built as one item in one session** (owner, 2026-09-27), with subagents doing the reading and building. Japanese reading UX and Yomitan's ease of use are improved wherever the agent sees fit, without editing Yomitan's files (D-002), so automatic Yomitan updates keep working. | 2026-09-27 |
| D-028 | **Phase 4 is built as one item in one session** (owner, 2026-09-28: "complete the entirety of Phase 4 in an efficient way"), with subagents doing the reading and building, as Phase 3 was (D-027). 4.5's list is still put to the owner, who picks what gets built. | 2026-09-28 |
| D-029 | **In the Japanese reader a tap on a word looks it up, and that is configurable** (owner, 2026-09-28). Swipes and the volume keys turn pages, a tap off the text opens the menu; a setting switches taps to turning pages (long-press still looks up). | 2026-09-28 |
| D-030 | **Tsundoku parity (4.5): build the status bar, short chapters marked read, chapter translation and EPUB export** (owner, 2026-09-28). Not now: read-aloud background options, saved passages (Phase 6's mining log covers them). | 2026-09-28 |
| D-031 | **Leave nothing behind; the project takes only the space it needs** (owner, 2026-09-28: "I don't have a lot of storage in any of the devices"). When an agent is done with the tablet, the phone, the computer or GitHub it removes what it put there: debug, benchmark and test apps, test folders, recordings, one-off build outputs, scratch files, superseded CI caches. What stays is kept as small as it can be without loss: `scripts/fork/gw` builds one debug APK (arm64, both devices) without Gradle's local build cache and prunes its logs after three days; the session-start hook deletes earlier sessions' scratch folders after a day; releases keep the newest three, their R8 mappings 30 days. The owner's own apps, folders and files are never touched; anything of theirs that looks removable is only pointed out. | 2026-09-28 |
| D-032 | **The plan after Phase 4** (owner, 2026-09-28): Phase 5 is a first-run Japanese setup and local EPUB/TXT books; manga lookup comes next (Phase 6, designed in D-034), then the learning extras (Phase 7: mining log, known-word colouring and one-unknown-word sentences, sentence audio). Sentence translation from the popup and an Aozora Bunko source stay ideas; ttu progress sync and audiobook read-along are dropped. | 2026-09-28 |
| D-033 | **A phase runs as two or three sessions, one item group each** (owner, 2026-09-28; replaces the one-session phases of D-027 and D-028). Phases 3 and 4 each took one session of 8-14 hours and about $190-215, peaking near 700k tokens of context; cost grows with context times steps, so smaller sessions cost about half. The owner starts each with `/next` in a new conversation. | 2026-09-28 |
| D-034 | **Manga lookup is a tap on the word, with the text found on the device** (owner, 2026-09-28, after a comparison of the options and their cost). Each page's text lines are found and read in the background when the page is shown (PP-OCRv6 manga models on ncnn, CPU), so a tap gives the same popup as in novels; a long-press-and-drag box is only the fallback for text the detector missed. The manga-trained model is used although it was trained partly on non-commercial data: the app never bundles it (downloaded from its author on request, deletable) and is not sold; the stock PP-OCRv6 model is the licence-clean fallback. No cloud OCR (never Google Lens). `.mokuro` is not planned. Research: `docs/fork/research/manga-ocr-2026-09.md`. | 2026-09-28 |

## Open

Questions that need the owner. Each has options and a recommendation; the agent that reaches the
item asks with AskUserQuestion and moves the answer to *Standing decisions*.

- **O-003 Is Reikai JP worth continuing next to Chimahon?** (raised by the owner, 2026-09-28, on learning that Chimahon and Yomihon exist). Chimahon (GPL, weekly releases since 2026-03) already has web novels through LNReader plugins, EPUB import, reading statistics with ttu sync, manga OCR (Google Lens by default), `.mokuro`, screen lookup in any app and an anime player; Yomihon is manga only. What only Reikai JP has: Yomitan's own engine (desktop-identical results, Handlebars card templates, settings import, automatic Yomitan updates), no cloud or closed binaries anywhere, Reikai's library, and a fork that maintains itself for the owner. Options: keep building as planned; build only what sets it apart and use Chimahon for the rest; pause the roadmap (the automation keeps the app updated at no cost). Recommendation: the owner reads with both apps for a few days first, and an agent can compare them side by side on the tablet in a short session (the same novel: lookup speed, memory, the card each one makes). **Ask before starting 5.1.**

## Log

- **2026-09-28** Owner, on manga lookup: "drawing a box is an extra step that maybe isn't necessary with a proper text detection implementation"; after the comparison chose a tap with text found automatically, the box only as a fallback, and the manga-trained model (D-034).
- **2026-09-28** Owner, on how to continue: Phase 5 gets the first-run setup besides local books (D-032); manga lookup before learning extras; phases in two or three sessions (D-033); drop ttu sync and audiobook read-along; keep automatic manga text detection and sentence audio. Noted by the agent, not decided: the repository is public, and GitHub does not bill standard-runner minutes for public repositories, so D-021's premise no longer holds; its practice (prove locally, never burn CI runs) stays.
- **2026-09-28** Owner, after Phase 4: present the project well (repository name and description, a README written for readers with screenshots and clips from both devices, lighter looks preferred, vertical text in landscape and horizontal text in portrait; the tablet's sideways scrolling as a clip was their idea); put the newest Reikai JP release, not a debug build, on both devices (the phone had not received Phases 3 and 4); clean every leftover from the devices, this computer and the repository and make it a principle (D-031); then review how the roadmap continues.
- **2026-09-28** Owner, starting Phase 4: build all of it in one session, efficiently (D-028). Taps in
  the Japanese reader: "look up by default, but make it configurable" (D-029). From the Tsundoku
  list: status bar, short chapters read, chapter translation, EPUB export (D-030).
  Also: besides pages, continuous scrolling to the end of the chapter in both directions, horizontal
  text scrolling down and vertical text scrolling sideways (4.1's pages-or-scrolling setting).
- **2026-09-27** Owner, starting Phase 3: build all of Phase 3 at once with subagents (D-027);
  Yomitan switchable, on by default ("a japanese native may want to use the app and find the popup
  annoying and it consumes RAM as well", D-025); the Japanese reader starts vertical with a one-time
  offer of horizontal, the standard reader only from the menu (D-026). Reported: on Kakuyomu,
  Firefox selects a whole word where upstream Reikai's reader selects one kanji and the rest has to
  be dragged. Tablet connected for the session; ask for the phone only when it gives better results.
- **2026-09-27** Owner, on the Yomitan spike: the test card goes in a test deck that is deleted
  afterwards; no note type in use yet, so Lapis 1.7.0 was added to the collection. With the numbers:
  "Go" (now D-024); the spike's test data removed from both devices, Lapis kept.
- **2026-09-27** Owner, starting Phase 2: "if I connect the device it's because I have this
  intention"; change the settings needed ("especially to keep them awake") and set them back after,
  telling them when each is changed and restored, as a rule for every later phase (now D-023).
- **2026-09-27** Owner answered the Phase 1 questions: don't offer the three upstream fixes
  (D-022); narrow the push guard so it blocks only real pushes to upstream Reikai (approved); test
  on the phone too ("I'm back and just plugged the phone in, keep it awake during tests").
- **2026-09-27** Owner, on roadmap item 1.6: "there's no need to run 20 CI runs, that sounds like
  a waste and I'm on the free plan on github. Make sure you can guarantee it without wasting runs"
  (now D-021). Also asked the agent to finish the rest of Phase 1 in one session and to keep the
  tablet screen on meanwhile.
- **2026-09-27** Owner chose to keep the Sources screen's novel language switches separate from
  the shared extension language filter (O-001, now D-020).
- **2026-09-27** Owner: "my devices will not always be on or connected, if you need them just send
  a user question and I'll answer after connecting the specific device needed. Make sure it's a
  rule as well." And: the tracked upstream branch "will likely not remain the most updated one for
  that long considering it's a feature branch"; there should be a check that it is still the right
  one (now at every session start).
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
