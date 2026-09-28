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
4. **One lookup popup for every surface.** Novel text, manga OCR and text selected in other apps
   all open the same popup, so a feature added once works everywhere (the dictionary screen is
   Yomitan's own search page).
5. **Performance is a feature.** Every surface has a budget (below) and is measured on the device.
6. **GPL-3.0-or-later, free of proprietary SDKs.** Builds use the default `local` distribution
   profile (no Firebase). Dictionaries, audio and OCR models are never bundled; the reader
   supplies them or the app downloads them on request.

## The pieces

```
 Surfaces       Japanese reading mode  Native text mode  Manga reader  Other apps
 (tap -> text)  (WebView, vert/horiz)  (long-press)      (OCR boxes)   ("Look up" menu)
                        └──────────── text + sentence + offset ────────────┘
                                             ▼
 One popup      Yomitan's popup.html in a sheet the app places, clear of the word
                                             ▼ ▲
 Engine         Unmodified Yomitan in a hidden WebView at https://yomitan.reikai.invalid:
                dictionaries, deinflection, frequency, pitch, audio sources, Anki templates
                                             ▲ small browser-extension stand-in (ours)
 Services       Kotlin: AnkiDroid bridge · audio fetch + local audio · settings storage
```

### 1. Yomitan engine (a fork module, GPL)

- **What:** Yomitan's real code, the release pinned in the fork module `jp-yomitan/`
  (`src/main/assets/yomitan/`, unzipped, recorded in `yomitan-release.json`), runs in a hidden
  WebView served at its own origin, `https://yomitan.reikai.invalid` (RFC 6761: never a real site;
  never upstream's `appassets.androidplatform.net`, where upstream's reader serves chapter images).
  Its dictionary database (Yomitan's own IndexedDB) lives there, one per app, independent of any
  novel site, so the origin never changes. Settings → Advanced → "Clear WebView data" (a seam)
  clears every other origin and all cookies but keeps the engine's IndexedDB.
- **How it runs:** the same path Yomitan's Firefox build uses. With no `chrome.offscreen`,
  the backend builds its database and translator in-process (`ext/js/background/backend.js:67-73`).
  An app-scoped `YomitanEngine`, reference-counted by the Japanese reader, the popup, the
  dictionary and settings screens, loads `background.html`; it never starts while lookup is off
  (D-025), starts in a reader once the page has been still for 2 s, stops 60 s after its last user
  (at once when lookup is switched off), and restarts after a renderer crash (three times in five
  minutes at most).
- **The stand-in** (`jp-yomitan/src/main/assets/jp-reikai/stand-in.js`, injected by
  `addDocumentStartJavaScript` into engine-origin documents only): the browser-extension
  functions Yomitan calls (runtime and tab messaging and ports, `storage.local` kept by the app in
  one `jp_` preference so backups carry it, `storage.session` in the app, permissions, logged
  no-ops), routed by the app's hub (`YomitanHub`), one WebView per "tab". It covers what the Phase 2
  spike found missing ([findings](research/yomitan-spike-2026-09.md)): a fake
  `navigator.serviceWorker` plus a page-local copy of Yomitan's database worker for dictionary
  pictures (no SharedWorker in WebView), zip.js inflating in-thread for imports (no worker started
  by a worker), `tabs.query` listing the real tabs, and every cross-origin request (AnkiConnect,
  audio, dictionary downloads) sent through the app. A **tripwire** logs any extension API Yomitan
  calls that the stand-in does not provide, so a new Yomitan version fails in tests, not silently.
- **Updates:** `fork-yomitan-update.yml` every Wednesday takes the newest promoted Yomitan release
  once it is 7 days old, vendors it (`scripts/fork/yomitan_bump.py`), and applies it by itself
  (decision D-015) when two checks pass: a static tripwire (the `chrome.*` calls, actions,
  permissions, database schema, call sites and page markup the stand-in and the app rely on,
  compared with the reviewed baseline `jp-yomitan/yomitan-surface.json`) and a smoke test in
  headless Chrome (the release with the stand-in and a JavaScript copy of the hub imports Yomitan's
  test dictionary, looks a word up, draws a picture and gets an AnkiConnect answer). Yomitan's own
  test suites are not run: they test its source tree with their own mocks, nothing of the
  stand-in's. A failure waits for the next agent session
  ([how it works](../../scripts/fork/yomitan-smoke/README.md)). Nothing in Yomitan's files is
  edited by hand.
- **Escape hatch:** if IndexedDB proves too slow on a phone, only the database layer changes. The
  translator touches eight database functions (`findTermsBulk`, `findTermMetaBulk`, ...), which can
  be backed natively (hoshidicts' GPL branch fits the licence) without touching lookup, UI or Anki.

### 2. Native services (Kotlin)

- **Anki:** Yomitan already speaks the AnkiConnect protocol. The app answers it in-process by
  translating to AnkiDroid's content provider (`content://com.ichi2.anki.flashcards`,
  `jp-yomitan/.../anki/`): add note, duplicate check through Anki's first-field checksum (4-11 ms
  per popup), media through the module's own `AnkiMediaProvider`, "view note" through AnkiDroid's
  card browser. No extra app, no open network port. `findCards` and `cardsInfo` use AnkiDroid
  2.24's `cards` table; card flags are not exposed.
- **Audio** (`jp-yomitan/.../audio/`): online sources are fetched natively (a WebView page cannot,
  for cross-origin rules); the community `android.db` local audio collection is copied once into
  app storage from the file picked (Android refuses SQLite on the picked file in place), served
  under AnkiConnect Android's `/localaudio/` scheme and listed first; the device's Japanese voice
  answers last as a custom source, so its audio reaches cards (WebView has no `speechSynthesis`).
- **Settings:** Yomitan's option blob is stored by the app (one `jp_` preference), so it is backed
  up and survives a Yomitan update, and Yomitan's own migration code upgrades it. A fresh profile
  gets phone-friendly defaults once (`MobileDefaults`). Settings → Japanese holds the lookup
  switch, dictionaries, AnkiDroid, local audio and text-to-speech, and opens Yomitan's own
  settings page for the rest.

### 3. The lookup popup

- Yomitan's own results page (`popup.html`) in a sheet the app places (`jp-yomitan/.../popup/`,
  `app/.../jp/reikai/lookup/LookupSheet.kt`): exact Yomitan tags, frequencies, pitch graphs,
  audio, add-to-Anki with duplicate state, the kanji tab, and back/forward for words looked up
  inside results. One loaded page serves every lookup: the lookup's `query`, `full` and `offset`
  go in its address and the rest (sentence, page URL and title, theme) in `history.state`, swapped
  by `jp-reikai/popup-host.js` with `history.replaceState` plus a `popstate` event. The page
  reloads when Yomitan's options or dictionaries change or the reader's theme does.
- The sheet: at the bottom, or at the top when at the bottom it would cover the word; drag to
  resize, swipe to close, width capped (about 640 dp) on tablets, in the page's theme. No floating
  card or pin. It parks transparent at full size between lookups, and one popup moves between
  screens (`MutableContextWrapper`), closing 60 s after the last one lets it go. The lookup runs
  while the selection toolbar shows, so "Look up" opens results already loaded.
- Designed against Yomitan's known mobile complaints: page scroll while selecting in the popup,
  search box out of thumb reach, fragile dictionary imports.

### 4. Scanners (how a tap becomes text)

| Surface | How |
|---|---|
| Japanese reading mode | Yomitan's own text-scanning code in the page: vertical text, furigana skipping and sentence rules behave like desktop Yomitan |
| Upstream's native and WebView text modes | Long-press selection. A fork `TextClassifier` (`app/.../jp/reikai/reader/JpTextClassifier.kt`, on each chunk `TextView` and the WebView) answers Japanese text with ICU's Japanese word around the character under the finger, extended by the dictionary's longest match when the engine is warm (150 ms cap), and puts "Look up" first in the selection toolbar |
| Manga | OCR boxes in image coordinates, mapped through the page view's zoom and pan |
| Other apps | Android's text-selection menu ("Look up in Reikai JP", `ACTION_PROCESS_TEXT`, `LookupActivity`: the sheet over the other app; switched off with lookup) |

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
- **Security:** upstream's `NovelWebBridge` checks a per-document token on every call (its object
  reaches every frame). The engine's documents reach the app only through `addWebMessageListener`
  restricted to the engine origin, and the app's hub decides each document's rights from what
  WebView reports (`sourceOrigin`, `isMainFrame`, which WebView sent it), never from the page:
  chapter pages (Phase 4) get content-script messaging within their own tab; Anki, network,
  storage and saves only the engine origin's main frames in the engine, settings and search
  WebViews. Engine pages other than `popup.html` are served with `frame-ancestors 'self'`, so no
  web page can frame Yomitan's settings. File-URL and content access stay off; nothing secret is
  logged.

## Performance budgets

Targets, with what Phase 3 measured on the owner's tablet (Tab S10 FE) and phone (A54) in brackets,
and the Phase 2 spike's figures where they compare ([spike](research/yomitan-spike-2026-09.md),
[Phase 3](research/phase3-design-2026-09.md)); enforced by measurement.
`scripts/fork/perf.py` measures the app's own paths on both devices ([perf/](perf/README.md));
the engine is measured on the debug build with `scripts/fork/yomitan_check.py` (`mem` for app and
renderer PSS, `cmd` with `bench` for lookups).

| Path | Target |
|---|---|
| Tap to popup visible (engine warm) | p95 under 150-200 ms (toolbar tap to painted results, native mode: tablet median ~53, p95 ~75 ms, n=23, after the final fixes, p95 102 ms before them; phone median 84, max 108 ms; phone WebView mode median 87, max 115 ms. Spike: tablet 108, phone 99) |
| Lookup from another app, engine cold | no target yet (tablet ~1.7 s after `am kill`; phone 4.2 s with the app not running) |
| Engine warm-up when a reader opens | off the critical path, under 1 s (tablet with Jitendex 920-942 ms, 1.05 s seen later; spike 1.4-1.6 s tablet, 2.1-2.5 s phone with three dictionaries; a reader's first lookup 3.5 s after the chapter opened found it warm) |
| Lookup in the engine (`termsFind`, 300 of 16 characters) | inside the tap budget (tablet p95 20 ms; spike 37.6) |
| JMdict import (one-time) | completes without crashing, under 5 min (Jitendex from its URL in Yomitan's settings: 147 s tablet, 184-197 s phone) |
| App cold start | not slower than upstream (the engine never starts at app start) |
| Chapter open in Japanese mode | not slower than upstream's WebView mode |
| Engine memory | the engine alone under +200 MB in total (app PSS growth plus its WebView renderer) on both devices, by `yomitan_check.py mem` (tablet: app 209-217 → 278-289 MB plus a 76-79 MB renderer; phone: 240-256 → 264-287 MB plus 98-103 MB; at most about +160 MB on either. Reading with the sheet open: tablet native reader 452 + 119 MB, phone WebView reader 447 + 141 MB) |

After the engine stops, WebView keeps its renderer (about 93 MB) until the app restarts, so
D-025's "costs no memory" holds from an app start with lookup off, not the moment it is switched
off.

## Risks and fallbacks

| Risk | Fallback |
|---|---|
| Yomitan cannot be hosted in a WebView after all | Not seen in the spike or in Phase 3. If it happens: GeckoView running Yomitan as a real extension (about +50 MB per phone architecture, unverified), or a native engine |
| IndexedDB too slow on the phone | The database escape hatch above |
| Upstream reorganises the reader again | The Japanese mode is a separate viewport behind upstream's interface; only the one seam moves |
| Yomitan stops being maintained | The pinned release keeps working; its stable contracts (dictionary format, markers) make a minimal fork of it feasible |
