# Reikai JP architecture

How the Japanese-immersion layer fits on top of Reikai, and the rules that keep the fork cheap to
maintain. Decisions behind it: [decisions.md](decisions.md). The evidence: [research/landscape-2026-09.md](research/landscape-2026-09.md).
What gets built when: [ROADMAP.md](ROADMAP.md).

## Goal

A manga and light-novel reader for learning Japanese: tap any Japanese word while reading, get a
Yomitan popup (dictionaries, conjugation, frequency, pitch accent, audio), add it to Anki in one
tap, and read Japanese novels the way they are printed (vertical or horizontal, furigana, pages),
while staying a thin, mergeable layer over Reikai.

## Principles

1. **Upstreams stay upstream.** Reikai is merged with git from its newest development branch;
   Mihon arrives through Reikai; Yomitan runs unmodified from a pinned release; LNReader plugins
   run on Reikai's existing host. The fork never re-implements what an upstream already does.
2. **Fork code lives in fork places.** New Kotlin under the `jp.reikai` package (and fork Gradle
   modules), fork docs under `docs/fork/`, fork scripts under `scripts/fork/`. An edit to an
   upstream file is a *seam*: minimal, fenced `// FORK -->` ... `// FORK <--`, listed by
   `scripts/fork/seams.py`. Fewer seams means cheaper merges.
3. **Imitate public contracts, never private internals.** Yomitan is adapted through a stand-in for
   the browser-extension API (a public standard) and an AnkiConnect-compatible bridge (a stable
   protocol), not by patching Yomitan's files.
4. **One lookup popup for every surface.** Novel text, manga OCR, the dictionary screen and text
   selected in other apps all open the same popup, so a feature added once works everywhere.
5. **Performance is a feature.** Every surface has a budget (below) and is measured on the device.
6. **GPL-3.0-or-later, free of proprietary SDKs.** Builds use the default `local` distribution
   profile (no Firebase). Dictionaries, audio and OCR models are never bundled; the reader
   supplies them or the app downloads them on request.

## The pieces

```
 Surfaces       Japanese reading mode  Native text mode  Manga reader  Other apps
 (tap -> text)  (WebView, vert/horiz)  (text offsets)    (OCR boxes)   ("Look up" menu)
                        └──────────── text + sentence + offset ────────────┘
                                             ▼
 One popup      Yomitan's own results page in an overlay (Reikai JP adds the phone chrome)
                                             ▼ ▲
 Engine         Unmodified Yomitan in a hidden WebView at a fixed private origin:
                dictionaries, deinflection, frequency, pitch, audio sources, Anki templates
                                             ▲ small browser-extension stand-in (ours)
 Services       Kotlin: AnkiDroid bridge · audio fetch + local audio · settings storage
```

### 1. Yomitan engine (a fork module, GPL)

- **What:** Yomitan's real code, the release pinned in the repository, runs in a hidden WebView
  served from a fixed private origin inside the app. Its dictionary database (Yomitan's own
  IndexedDB) lives there, one per app, independent of any novel site.
- **How it runs:** the same path Yomitan's Firefox build uses. With no `chrome.offscreen`,
  the backend builds its database and translator in-process (`ext/js/background/backend.js:67-73`).
- **The stand-in:** a small script loaded before Yomitan provides the browser-extension functions
  Yomitan calls (runtime messaging, `storage.local/session`, permissions, a few no-ops), and hands
  the backend a message port directly (Yomitan otherwise waits on a service worker that a WebView
  never registers, `ext/js/comm/api.js:488-505`). A **tripwire** reports any extension API Yomitan
  calls that the stand-in does not provide, so a new Yomitan version fails in tests, not silently.
- **Updates:** a workflow checks Yomitan releases, vendors the new one, runs Yomitan's own
  golden-file tests through the stand-in in Node, and applies it by itself when they pass
  (decision D-015); a failure waits for the next agent session. Nothing in Yomitan's files is
  edited by hand.
- **Escape hatch:** if IndexedDB proves too slow on a phone, only the database layer changes. The
  translator touches eight database functions (`findTermsBulk`, `findTermMetaBulk`, ...), which can
  be backed natively (hoshidicts' GPL branch fits the licence) without touching lookup, UI or Anki.

### 2. Native services (Kotlin)

- **Anki:** Yomitan already speaks the AnkiConnect protocol. The app answers it in-process by
  translating to AnkiDroid's content provider (`content://com.ichi2.anki.flashcards`): add note,
  duplicate check through Anki's first-field checksum, media through a `FileProvider`, open the
  note in AnkiDroid. No extra app, no open network port. Card-state reads (for known-word features)
  need AnkiDroid 2.24 or newer and are detected at runtime.
- **Audio:** online sources are fetched natively (a WebView page cannot, for cross-origin rules);
  the community `android.db` local audio collection is read directly; Android TTS is the offline
  fallback.
- **Settings:** Yomitan's option blob is stored by the app, so it is backed up and survives a
  Yomitan update, and Yomitan's own migration code upgrades it.

### 3. The lookup popup

- Yomitan's own results page (`popup.html`, driven by URL parameters `query`, `full`, `offset`,
  `ext/js/display/display.js:836`) in an overlay: exact Yomitan tags, frequencies, pitch graphs,
  audio, add-to-Anki with duplicate state, the kanji tab, and back/forward for words looked up
  inside results.
- Reikai JP adds the phone parts around it: bottom sheet or floating card, drag handle, swipe to
  dismiss, pin open, controls within thumb reach; styling through Yomitan's own custom-CSS option.
- Designed against Yomitan's known mobile complaints: page scroll while selecting in the popup,
  search box out of thumb reach, fragile dictionary imports.

### 4. Scanners (how a tap becomes text)

| Surface | How |
|---|---|
| Japanese reading mode | Yomitan's own text-scanning code in the page: vertical text, furigana skipping and sentence rules behave like desktop Yomitan |
| Native text mode (upstream's) | `TextView` offsets at the tap point, ruby spans skipped |
| Manga | OCR boxes in image coordinates, mapped through the page view's zoom and pan |
| Other apps | Android's text-selection menu ("Look up in Reikai JP", `ACTION_PROCESS_TEXT`) |

### 5. Japanese reading mode

- A third novel viewport beside upstream's native and WebView modes, in its own files, plugged in
  through the one place the reader picks a viewport (`NovelReaderProvider.createViewport`,
  `app/src/main/java/reikai/presentation/reader/NovelReaderProvider.kt:273`) behind upstream's
  `ReaderViewport` interface. It reuses upstream's chapter list, progress, history and tracking.
- **The reader chooses vertical or horizontal text**, pages or continuous scroll; ttu's four
  furigana modes; `lang="ja"`; Japanese fonts; Japanese line breaking; position kept by character
  so font changes never lose the place; ttu-compatible character counts.
- **Default for Japanese text, never forced.** Japanese is detected from the source's language
  (`NovelSource.lang`, mapped to ISO codes by upstream) or, for local books, from the text itself.
  The first time a novel opens in Japanese mode the reader says what it is and where the switch
  is; one tap in the reader menu goes back to the standard reader, remembered per novel (the
  novel's `viewerFlags`, where 0 already means "follow the default").

### 6. Local books, learning extras, manga lookup (later phases)

- **Local EPUB/TXT:** one more source adapter behind upstream's `NovelSource` seam.
- **Learning extras:** a mining log with a jump back to the passage; known-word and frequency
  colouring from Yomitan's tokenizer plus AnkiDroid card state (Yomitan itself declined in-page
  highlighting); i+1 sentences; TTS sentence audio; reading statistics.
- **Manga:** an overlay on `ReaderPageImageView` (the one view both manga viewers share); first
  `.mokuro` import, then a region the reader draws read by manga-ocr (Apache-2.0) downloaded on
  demand, then automatic detection (PaddleOCR manga models). Never the redistributed Google Lens
  binaries some forks use. Mihon's WebGPU viewer is left out at first.

## Fork plumbing rules

- **Dependency injection (Metro):** fork classes join the graph with `@Inject` /
  `@ContributesBinding`, and fork accessors through a fork-owned `@ContributesTo(AppScope::class)`
  interface, so upstream's `AppGraph.kt` and `ReikaiGraph.kt` stay untouched.
- **Persistence:** the fork keeps its own database (a fork module's SQLDelight or plain SQLite).
  It never adds a migration to upstream's `.sqm` sequence or a preference migration to upstream's
  `versionCode` gates, which would collide on the next merge. Fork preference keys start with
  `jp_`.
- **Strings:** fork strings live in fork resources, not upstream's high-churn `strings.xml`.
- **R8:** a new top-level package using reflection-based DI needs a proguard keep; check with a
  minified build (`:app:assembleNightly`) before trusting it.
- **Security:** WebView bridges validate the calling origin and a per-document token (upstream's
  `NovelWebBridge` pattern); file-URL access stays off; nothing secret is logged.

## Performance budgets

Targets to confirm in the Phase 2 spike on the owner's tablet and phone, then enforced by measurement:

| Path | Target |
|---|---|
| Tap to popup visible (engine warm) | p95 under 150-200 ms |
| Engine warm-up when a reader opens | off the critical path, under 1 s |
| JMdict import (one-time) | completes without crashing, under 5 min |
| App cold start | not slower than upstream (engine starts lazily) |
| Chapter open in Japanese mode | not slower than upstream's WebView mode |
| Engine memory | measured in the spike; the budget is set there |

## Risks and fallbacks

| Risk | Fallback |
|---|---|
| Yomitan cannot be hosted in a WebView after all | GeckoView running Yomitan as a real extension (about +50 MB per phone architecture, unverified), or a native engine |
| IndexedDB too slow on the phone | The database escape hatch above |
| Upstream reorganises the reader again | The Japanese mode is a separate viewport behind upstream's interface; only the one seam moves |
| Yomitan stops being maintained | The pinned release keeps working; its stable contracts (dictionary format, markers) make a minimal fork of it feasible |
