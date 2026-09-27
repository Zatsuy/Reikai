# Research: the Yomitan spike (roadmap 2.1), September 2026

**Question:** can Yomitan's own, unmodified code run inside Reikai JP fast enough to be the lookup
engine? **Answer from the devices: yes.** Every budget in [architecture.md](../architecture.md) was
met except engine warm-up (1.5-2.1 s instead of 1 s, which the design already keeps off the
critical path). One feature is still broken, dictionary pictures; it has a known fix.

Measured on 2026-09-27 on the owner's Galaxy Tab S10 FE (SM-X520) and Galaxy A54 (SM-A546E), both
Android 16 with WebView 153, in the debug build (`app.reikai.jp.dev`, not minified). Code:
`app/src/debug/java/jp/reikai/yomitan/spike/`, `app/src/debug/assets/jp-reikai/yomitan-spike/`,
driver `scripts/fork/yomitan_spike.py` (its docstring has the commands to repeat every number).

## Setup

- Yomitan **26.9.8.0**, the official `yomitan-firefox.zip` (SHA-256 `8c23aa2d...5375217`), files
  untouched, served at the root of `https://appassets.androidplatform.net/` by
  `shouldInterceptRequest` from files pushed over adb.
- Dictionaries: Jitendex 2026-08-11 (JMdict-based, 38.7 MB zip, 435,448 terms, 236 media files),
  JPDB v2.2 frequency (kana), Kanjium pitch accents. Together 320 MB of IndexedDB.
- Anki: AnkiDroid 2.24.0 with the Lapis 1.7.0 note type, created through the content provider from
  Lapis's own templates.
- One WebView is one "tab": an invisible engine WebView (`background.html`), a driver page, and a
  vertical-text reader page into which Yomitan's own content script is loaded, as a browser would.

## Results

| | Tablet | Phone | Budget |
|---|---|---|---|
| Engine ready (backend `prepare` done, from WebView load) | 0.6-0.8 s empty, 1.5-1.6 s with 3 dictionaries | 1.0 s empty, 2.1 s | under 1 s, off the critical path |
| Import Jitendex / JPDB / Kanjium | 145 s / 77 s / 30 s | 213 s / 122 s / 51 s | JMdict under 5 min, no crash |
| Lookup (`termsFind` over the full message path, 300 lookups of 16 characters) p50 / p95 / max | 15.5 / 37.6 / 55 ms | 18.1 / 54.3 / 125 ms | |
| **Tap to popup drawn** (finger up to the results painted, real `input tap`) p50 / p95 / max | **88 / 108 / 115 ms** (n=24) | **64 / 99 / 99 ms** (n=20, first popup left out) | p95 under 150-200 ms |
| First popup after the reader opens (the popup frame loads) | 340-370 ms | 342 ms | |
| Memory with the reader and a popup open: app / WebView renderer (PSS) | 364 / 205 MB | 315 / 161 MB | set here |
| Same, the plain app on its library (no WebView) | 217 MB | 268 MB | |
| Yomitan's JavaScript heap, all four documents | 28 MB | | |
| Tripwire (extension APIs used but not provided) | none | none | |

What a reader gets is Yomitan's real popup over vertical text: deinflection (食べられなかった finds
食べられる and 食べる), JPDB frequency ranks, the Kanjium pitch graph, Jitendex definitions, ruby
text skipped (喫茶店 read from its furigana), the scanned word highlighted.

**Anki, end to end:** tapping Yomitan's own add button made a Lapis note in the test deck with
Expression, ExpressionFurigana `喫茶店[きっさてん]`, ExpressionReading, the Jitendex MainDefinition
and Glossary, the sentence Yomitan cut from the vertical text with the word in bold, PitchPosition
`[3]` `[0]`, PitchCategories `nakadaka,heiban`, Frequency, FreqSort `5436` and MiscInfo (the page
title). Yomitan also fetched the word's audio by itself; the spike does not store media yet
(Phase 3.3). The note was then deleted, and the test deck removed through AnkiDroid's screen
(its content provider cannot delete decks). Lapis stays in the owner's collection.

## What the stand-in needed (facts for Phase 3.1)

1. **Serve at the origin root.** Pages, workers and `fetch` use root paths (`/js/...`,
   `/lib/resvg.wasm`); a path prefix breaks them.
2. **The `chrome.*` set.** runtime (messaging with `lastError`, `onMessage` promise and
   `sendResponse` replies, `getManifest` synchronous, `getURL`, `getPlatformInfo`), `storage.local`
   (localStorage of the origin) and `storage.session`, permissions, tabs (`sendMessage`, `connect`,
   `query`, `get`, `getCurrent`), ports (`tabs.connect` / `runtime.onConnect`, which Yomitan's
   cross-frame API uses between a page and its popup), and no-ops for action, commands,
   contextMenus, omnibox, declarativeNetRequest, scripting, windows. `chrome.offscreen` must stay
   absent: Yomitan then runs its database in the backend page, as in Firefox. The tripwire reported
   nothing in every run whose log was read (engine start, reader, popup, adding a card).
3. **`tabs.query` must list the real tabs.** Yomitan announces changed settings to every tab it
   finds; with none listed, an open popup never learned that Anki was switched on.
4. **Android WebView has no SharedWorker.** The backend opens one unconditionally when it runs as a
   page; an inert one lets it start. The channel it carries draws dictionary pictures, so
   **pictures do not draw yet** (the popup keeps an empty frame; 馬酔木 in Jitendex shows it). Pages
   also see `navigator.serviceWorker` and wait on a service worker that never comes for the same
   channel. Phase 3.1 fix: hide `serviceWorker` and give pages a SharedWorker whose handshake the
   app brokers, handing both ends of a `WebMessageChannel` to the backend and the page.
5. **A worker cannot start a worker.** Android WebView never serves a worker started from inside a
   dedicated worker (the request bypasses `shouldInterceptRequest`), and zip.js does exactly that in
   Yomitan's import worker, so the settings page's import hangs forever at its first step. Running
   Yomitan's `DictionaryImporter` in a page (as its own tests do) works: ordinary workers started by
   a page are served. Phase 3 imports from a hidden page, or from the settings page with the
   importer moved out of the worker by the stand-in.
6. **AnkiConnect through the app.** A WebView cannot intercept a POST body, so the stand-in routes
   the backend's `fetch` to the Anki server address to the app. Adding a card used `version`,
   `canAddNotesWithErrorDetail`, `storeMediaFile` and `addNote`.
7. **BroadcastChannel works across WebViews of one app** (same origin). A cheaper transport than
   routing every message through Kotlin, worth trying in 3.1.
8. **Yomitan's in-page popup does not fit a portrait phone with vertical text.** It opened mostly
   off the left edge and the page scrolled to show it. Phase 3.4's own phone popup (a bottom sheet
   or a floating card around Yomitan's results page) is needed, not optional.

## Budgets for Phase 3

- **Tap to popup:** keep p95 under 150 ms warm on both devices (measured 99-108 ms); open the
  popup frame when the reader opens, so no one waits 340 ms for the first word.
- **Engine warm-up:** start the engine when a Japanese novel's reader (or its details screen)
  opens, never at app start; lookups wait for it at most once.
- **Engine memory:** the WebView renderer held 111 MB with the engine and an idle page (tablet),
  and the reader with a popup adds 50-100 MB more; budget 150 MB for the engine's renderer share
  and measure again in 3.1 without the spike's driver page.
- **Imports:** Jitendex in 2.5-3.5 min, done in the background with progress; the three test
  dictionaries take 320 MB.
