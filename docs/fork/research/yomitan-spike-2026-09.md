# Research: the Yomitan spike (roadmap 2.1), September 2026

**Question:** can Yomitan's own, unmodified code run inside Reikai JP fast enough to be the lookup
engine? **Answer from the devices: it runs, and a warm lookup is fast.** Against the budgets in
[architecture.md](../architecture.md): tap to popup and the dictionary import met theirs; engine
warm-up missed its 1 s (1.4-2.5 s); the first popup after the reader opens takes 340-780 ms; app start
and chapter open were not measured (the spike has no Japanese reading mode yet). Not working yet:
dictionary pictures, Yomitan's own settings-page import (the spike imports from a page instead),
and Yomitan's in-page popup on a portrait phone. Fixes for the first two are proposed below, not
tested; the third is Phase 3.4's own popup.

Measured on 2026-09-27 on the owner's Galaxy Tab S10 FE (SM-X520) and Galaxy A54 (SM-A546E), both
Android 16 with WebView 153, in the debug build (`app.reikai.jp.dev`, not minified). Code:
`app/src/debug/java/jp/reikai/yomitan/spike/`, `app/src/debug/assets/jp-reikai/yomitan-spike/`,
driver `scripts/fork/yomitan_spike.py` (its docstring has the commands behind these numbers; the JavaScript heap figure came from DevTools).

## Setup

- Yomitan **26.9.8.0**, the official `yomitan-firefox.zip` (SHA-256 `8c23aa2d...5375217`), files
  untouched, served at the root of `https://appassets.androidplatform.net/` by
  `shouldInterceptRequest` from files pushed over adb.
- Dictionaries: Jitendex 2026-08-11 (built on JMdict with more data; it stands in for "JMdict" in
  the roadmap item; 38.7 MB zip, 435,448 terms, 236 media files),
  JPDB v2.2 frequency (kana), Kanjium pitch accents. Together 320 MB of IndexedDB.
- Anki: AnkiDroid 2.24.0 with the Lapis 1.7.0 note type, created through the content provider from
  Lapis's own templates.
- One WebView is one "tab": an invisible engine WebView (`background.html`), a driver page, and a
  vertical-text reader page into which Yomitan's own content script is loaded, as a browser would.

## Results

| | Tablet | Phone | Budget |
|---|---|---|---|
| Engine ready (backend `prepare` done, from the engine page starting to load; WebView itself was already loaded) | 0.6-0.8 s empty, 1.4-1.6 s with 3 dictionaries | 1.0 s empty, 2.1-2.5 s | under 1 s, off the critical path |
| Import Jitendex / JPDB / Kanjium | 145 s / 77 s / 30 s | 213 s / 122 s / 51 s | JMdict under 5 min, no crash |
| Lookup (`termsFind` over the full message path, 300 lookups of 16 characters) p50 / p95 / max | 15.5 / 37.6 / 55 ms | two runs: 18.1 / 54.3 / 125 and 25.2 / 88.1 ms | |
| **Tap to popup** (the reader's `pointerup` to two frames after Yomitan inserts the results; taps from `adb input tap`) p50 / p95 / max, warm | **88 / 108 / 115 ms** (n=24) | **64 / 99 / 99 ms** (n=20) | p95 under 150-200 ms |
| First popup after the reader opens (the popup frame loads) | 340-370 ms | 342-783 ms | |
| Memory with the reader and a popup open: app / WebView renderer (PSS) | 364 / 205 MB | 312-315 / 161-198 MB | set in 3.1 |
| Same, the plain app on its library (no WebView) | 217 MB | 268 MB | |
| **Extra memory while reading with a popup** (app growth plus renderer) | **about +350 MB** | **about +210-240 MB** | |
| Engine and the idle driver page only: app / renderer | 326-335 / 110-111 MB | 287 / 119 MB (181 MB after 300 lookups) | |
| Yomitan's JavaScript heap, all four documents (`performance.memory` over DevTools) | 28 MB | | |
| Tripwire (a missing property read on `chrome.*`) | none | none | |

The timing leaves out the touch screen's and the display's own latency (a few tens of ms on any
app), and counts warm taps: the first popup after the reader opens is listed on its own row. With
20-24 taps, p95 is close to the maximum. Memory figures are single readings.

Engine ready does not include loading WebView itself (a few hundred ms, once per app process); in
the real app a reader that already uses a WebView has paid that.

What a reader gets is Yomitan's real popup over vertical text: deinflection (食べられなかった finds
食べられる and 食べる), JPDB frequency ranks, the Kanjium pitch graph, Jitendex definitions, ruby
text skipped (喫茶店 read from its furigana), the scanned word highlighted.

**Anki, end to end (tablet only):** tapping Yomitan's own add button made a Lapis note in the test deck with
Expression, ExpressionFurigana `喫茶店[きっさてん]`, ExpressionReading, the Jitendex MainDefinition
and Glossary, the sentence Yomitan cut from the vertical text with the word in bold, PitchPosition
`[3]` `[0]`, PitchCategories `nakadaka,heiban`, Frequency, FreqSort `5436` and MiscInfo (the page
title). Yomitan also fetched the word's audio by itself; the spike does not store media yet
(Phase 3.3). The note was then deleted, and the test deck removed through AnkiDroid's screen
(its content provider cannot delete decks). Lapis stays in the owner's collection. The phone was
left out on purpose: creating Lapis on both devices before they sync would give the owner two
note types named Lapis, and the bridge is the same code on both.

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
   channel. Proposed fix for 3.1 (untested): hide `serviceWorker` and give pages a SharedWorker whose handshake the
   app brokers, handing both ends of a `WebMessageChannel` to the backend and the page.
5. **A worker cannot start a worker.** Android WebView never serves a worker started from inside a
   dedicated worker (the request bypasses `shouldInterceptRequest`), and zip.js does exactly that in
   Yomitan's import worker, so the settings page's import hangs forever at its first step. Running
   Yomitan's `DictionaryImporter` in a page (as its own tests do) works: ordinary workers started by
   a page are served. Proposed for Phase 3 (untested): import from a hidden page, or from the settings page with the
   importer moved out of the worker by the stand-in.
6. **AnkiConnect through the app.** A WebView cannot intercept a POST body, so the stand-in routes
   the backend's `fetch` to the Anki server address to the app. Adding a card used `version`,
   `canAddNotesWithErrorDetail`, `storeMediaFile` and `addNote`.
7. **BroadcastChannel works across WebViews of one app** (same origin). A cheaper transport than
   routing every message through Kotlin, worth trying in 3.1.
8. **Every page on the engine's origin has the engine's full power.** The stand-in sits in the
   page's own scripts (a browser keeps content scripts apart), so any script on that origin can
   call any backend action or reach AnkiDroid. Harmless for the spike's static test page; in
   Phase 3 chapter HTML must not share the engine's origin (or runs without scripts behind a
   token-checked bridge), and only the backend document may reach the Anki bridge.
9. **Yomitan's in-page popup does not fit a portrait phone with vertical text.** It opened mostly
   off the left edge and the page scrolled to show it. Phase 3.4's own phone popup (a bottom sheet
   or a floating card around Yomitan's results page) is needed, not optional.

## Budgets for Phase 3

- **Tap to popup:** keep p95 under 150 ms warm on both devices (measured 99-108 ms); open the
  popup frame when the reader opens, so no one waits 340-780 ms for the first word.
- **Engine warm-up:** start the engine when a Japanese novel's reader (or its details screen)
  opens, never at app start; lookups wait for it at most once.
- **Engine memory:** reading with a popup cost about +350 MB on the tablet and +210-240 MB on the
  phone in total, part of it any WebView reader's cost (upstream's WebView reader also doubles the
  app's memory, [perf](../perf/README.md)). Measure the engine alone in 3.1, without the spike's
  full-screen driver page, and set the budget there.
- **Imports:** Jitendex in 2.5-3.5 min, done in the background with progress; the three test
  dictionaries take 320 MB.
