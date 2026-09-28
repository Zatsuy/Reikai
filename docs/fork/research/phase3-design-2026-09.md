# Phase 3 design: the Yomitan engine, Anki, audio, the popup, settings, updates (September 2026)

The build plan for roadmap Phase 3 (items 3.1-3.6, built as one item, D-027), from three read-only
scouts on 2026-09-27 over the spike code, `../refs/yomitan` (26.9.8.0-1), upstream Reikai and the
reference apps. Owner decisions that shape it: D-002 (Yomitan unmodified), D-015/D-018/D-021
(automation, CI minutes), D-024 (go), D-025 (lookup switchable, on by default), D-026 (reader).
Spike facts: [yomitan-spike-2026-09.md](yomitan-spike-2026-09.md). Citations are to
`../refs/yomitan/ext/` unless a path says otherwise.

## Layout

- **Fork module `jp-yomitan/`** (Android library; two one-line seams: `include` in
  `settings.gradle.kts`, `implementation(project(":jp-yomitan"))` in `app/build.gradle.kts`;
  `androidx.webkit` moves from the app's `debugImplementation` to it). Owns its `AndroidManifest.xml`
  (AnkiDroid permission and `<queries>`, its activities), its `res/` (strings `jp_`), and:
  - `src/main/assets/yomitan/`: the vendored **unzipped** `yomitan-firefox.zip` of the pinned
    release, written only by `scripts/fork/yomitan_bump.py` (hard rule 3), with
    `yomitan-release.json` (version, zip URL and SHA-256, per-file SHA-256). Unzipped because
    assets are random-access and a bump's git diff holds only changed files (a committed zip adds
    15 MB to history per release). APK grows about 15 MB.
  - `src/main/assets/jp-reikai/`: the stand-in and small fork worker entry files.
  - `src/main/java/jp/reikai/yomitan/`: engine, hub, serving, bridges.
- **App glue** in `app/src/main/java/jp/reikai/` (anything touching upstream types: settings
  screen, reader hook, text classifier). DI through a fork `@ContributesTo(AppScope::class)`
  interface (pattern: `presentation-widget/.../di/PresentationWidgetGraph.kt:7-10`, read with
  `context.metroGraph<...>()`); classes `@Inject @SingleIn(AppScope::class)` like
  `jp/reikai/browse/ExtensionLanguages.kt`. Preferences: a fork `JpPreferences(preferenceStore)`,
  keys `jp_` (backed up for free by `PreferenceBackupCreator`). Strings: moko's Android
  `StringResource(resourceId)` wraps fork `R.string.jp_*`, so fork strings work in upstream's
  settings widgets.
- Add `jp-yomitan/` to `scripts/fork/owned-paths.txt`; Yomitan's licences (GPL-3.0, EDRDG, OFL
  fonts, KanjiVG CC BY-SA 3.0, npm licences from `legal-npm.html`) to `LICENSES/`.

## 3.1 Engine

- **Origin:** a fixed private https origin of its own, never upstream's
  `appassets.androidplatform.net` (upstream's WebView reader serves chapter images there,
  `reikai/presentation/reader/web/NovelWebImages.kt:63`). Proposed `https://yomitan.reikai.invalid`
  (RFC 6761: can never be a real site). IndexedDB and settings are tied to it, so it never
  changes after this phase. Yomitan at the root (its pages use root paths), fork files under
  `/__reikai/`, `application/wasm` kept.
- **Injection:** `WebViewCompat.addDocumentStartJavaScript(webView, standIn, {origin})` plus
  `addWebMessageListener("reikaiHub", {origin})` (spike `SpikeHub.kt:52-53`). Reaches every
  engine-origin frame, never workers. Serving-time edits of Yomitan's bytes are not allowed (they
  break the hash check and module imports hoist above prepended code).
- **Stand-in fixes over the spike** (`app/src/debug/assets/jp-reikai/yomitan-spike/stand-in.js`):
  `getURL` and the manifest use the fixed origin and an embedded manifest (not `location.origin`
  or a sync XHR); `storage.session` lives in the hub (the backend writes keys other pages read,
  `options-util.js:1172`, `backend.js:2857`); `storage.local` is app-backed (backed up, survives
  WebView data loss; as built, one `jp_` string preference, since upstream's backup carries only the
  app's preference file: about 16 KB stored for one profile, read again and the engine restarted
  when a restored backup replaces it); the document's role is decided natively from `sourceOrigin`, `isMainFrame`
  and which WebView sent it, never claimed by the page; no-op stubs log a "called" line so the
  tripwire sees reliance on them (`tabs.create` resolving `undefined` at `options-util.js:1869`);
  `browser` stays undefined (Chrome code paths). `chrome.offscreen` stays absent.
- **Opening pages:** `tabs.create`, `windows.*`, `runtime.openOptionsPage` (`backend.js:1481, 1502,
  1598, 2194, 2215, 2893, 2915`) route to the app: settings.html to the Yomitan settings screen,
  search.html to the search screen, welcome.html dropped, info/legal pages to an in-app WebView,
  http(s) links to the browser.
- **Dictionary pictures** (no SharedWorker in WebView): pages' `Application.main` starts a media
  worker and hands the backend one end of a MessageChannel through `connectToDatabaseWorker`
  (`application.js:193-222`, `comm/api.js:420, 488-505`), which waits on
  `navigator.serviceWorker.ready`. The stand-in replaces `navigator.serviceWorker` with a fake whose
  `ready` resolves to `{active:{postMessage}}`; on `connectToDatabaseWorker` it starts, lazily on
  the first `drawMedia`, a page-local copy of Yomitan's unmodified
  `/js/dictionary/dictionary-database-worker-main.js` (same origin, same IndexedDB) and posts it the
  port, exactly as `dictionary/dictionary-database.js:861-866` does. The backend's own DB worker
  can become an inert stub (used only for `drawMedia` and `connect`). A page-local worker blocks
  `purgeDatabase` (`database.js:375-380`): terminate page workers when a purge goes out and after
  idling. A MessagePort cannot cross a WebMessagePort or BroadcastChannel, so no app broker.
- **Imports** (a worker never starts a worker in WebView): the stand-in wraps `globalThis.Worker`
  in pages and swaps `/js/dictionary/dictionary-worker-main.js` for
  `/__reikai/workers/dictionary-worker-main.js` =
  `import '/__reikai/zip-inline.js'; import '/js/dictionary/dictionary-worker-main.js';` where
  `zip-inline.js` runs `configure({useWebWorkers:false})` on the shared `/lib/zip.js` instance
  (Yomitan's later `configure` sets only `workerScripts`). zip.js then inflates in-thread with
  `DecompressionStream`. Measure against the spike's 145 s (tablet Jitendex).
- **Chapter pages stay off the engine origin.** Content-role documents (a foreign `sourceOrigin`)
  may only `hello`, `sendMessage` an allowlist of content-script actions, and message within their
  own tab; `anki`, `fetch`, storage and downloads are accepted only from engine-origin main frames
  of engine, settings or search WebViews (the spike accepted `anki` from anyone,
  `YomitanSpikeActivity.kt:119`). Trap for Phase 4: on an https chapter page Yomitan's
  `inExtensionContext` protocol check (`application.js:194`) is true and `new Worker(<engine URL>)`
  throws; the content-role stand-in makes cross-origin `Worker` inert. Engine responses carry
  `Access-Control-Allow-Origin` for a requesting chapter origin (cross-origin module imports).
- **Vanished pages:** `forget(doc)` on a stand-in `bye` (`pagehide`), a replacing `hello`,
  `onPageStarted` of a main frame, WebView destroy, `onRenderProcessGone` and a failed post. It
  answers every pending request aimed at that document with lastError "The message port closed
  before a response was received.", disconnects its ports' other ends, and drops its own pending
  requests. No blanket timeouts (imports are long).
- **Network:** the backend's `fetch` goes through `RequestBuilder.fetchAnonymous`
  (`background/request-builder.js:52-93`); cross-origin sites without CORS fail in a WebView
  (jisho, languagepod101). The backend-role fetch wrapper sends every cross-origin http(s) request
  to the app (`{url, method, headers, body}`), run on OkHttp without cookies, cancellable through
  the `AbortSignal`, answered with status, headers and an ArrayBuffer body. `http://localhost:8765`
  and `http://127.0.0.1:8765` (AnkiConnect default, local audio) go to the app's in-process router.
  Pages' cross-origin GETs (dictionary import from URL, `dictionary-import-controller.js:461-482`;
  update check `dictionary-controller.js:178`) are proxied inside `shouldInterceptRequest` with
  `Access-Control-Allow-Origin` added.
- **Lifecycle and memory:** an app-scoped `YomitanEngine`, reference-counted by users (reader,
  search, settings, lookup activity); never started while lookup is off (D-025); started when a
  Japanese novel's reader opens (and by the search and lookup screens); destroyed about 60 s after
  the last user leaves, at once when lookup is switched off, on `onTrimMemory` past the grace
  period; restarted on `onRenderProcessGone` (as built: at once after a crash, three times in five
  minutes at most; after the system killed the renderer for memory, or a failure, only when a screen
  next needs it). One hidden WebView (1x1, alpha 0, attached, with a
  `MutableContextWrapper` so it moves between activities) loading `background.html`. Measure the
  engine alone (renderer PSS delta) and set the budget in `architecture.md`.
- **Risk:** Settings → Advanced → "Clear WebView data" runs `WebStorage.deleteAllData()` and deletes
  `app_webview/` (`SettingsAdvancedScreen.kt:316-317`), erasing every dictionary. Needs a seam
  that keeps the engine origin. Whether WebView grants `navigator.storage.persist()` is unverified.

## 3.2 Anki (raw AnkiDroid provider, no library)

AnkiDroid's API library is LGPL on JitPack and lacks search, card info and deck-scoped duplicate
checks, so the bridge uses `content://com.ichi2.anki.flashcards` directly (the spike's
`SpikeAnki.kt` proves the insert path). Every AnkiConnect action Yomitan calls
(`comm/anki-connect.js`, `background/backend.js`):

| Action | On AnkiDroid |
|---|---|
| version | 6 |
| deckNames, modelNames, modelFieldNames | `decks`, `models`, `models/<id>` field names; cache them |
| canAddNotesWithErrorDetail, canAddNotes | first-field checksum on `notes_v2`, batched in one `csum IN (...)`; same model unless `checkAllModels`; honour `duplicateScope` deck/deck-root and `checkChildren` via the cards' `deck_id`; error text contains `cannot create note because it is a duplicate` (`backend.js:657`). Runs on every popup: must be cheap |
| addNote, addNotes | insert into `notes`, move cards to the deck |
| updateNoteFields | update `notes/<id>` fields merged by name |
| findNotes, findCards | the query passed through as the `notes` selection (AnkiDroid runs it as an Anki search) |
| multi | run each sub-action, list of raw results |
| notesInfo, cardsInfo | `notes/<id>` and `notes/<id>/cards`; card id derived from (noteId, ord); flags 0 unless AnkiDroid 2.24's card-state columns exist |
| storeMediaFile | base64 to a cache file, the app's `FileProvider` (`${applicationId}.provider`), `grantUriPermission("com.ichi2.anki")`, insert `media`, return the stored name |
| guiEditNote | exactly `unsupported action` (Yomitan falls back only on that exact string, `anki-connect.js:431-436`; the spike's `"unsupported action $action"` is a bug) |
| guiBrowse | `anki://x-callback-url/browser?search=nid:..` |
| sync | intent `com.ichi2.anki.DO_SYNC` with a cooldown |
| suspend, anything else | `unsupported action` unless verified on AnkiDroid 2.24 |

Permission `com.ichi2.anki.permission.READ_WRITE_DATABASE` is runtime-granted: requested from
settings and from the popup's first add; the hidden engine only returns an error. Reference code:
chimahon `AnkiDroidBridge.kt` (GPL-3.0, credit if adapted), yomihon (Apache-2.0). The AnkiConnect
route must serve settings.html too (it builds its own `AnkiConnect`, `anki-controller.js:45`).

**As built (2026-09-27, `jp-yomitan/.../anki/`):** AnkiDroid 2.24's provider also has `cards`
(Anki search) and `cards/<id>`, so `findCards` and `cardsInfo` are real; card flags are not exposed
(0). The duplicate check is one `notes_v2` query (`csum IN (...)`) plus, for deck scopes, one
`notes_v2` query per distinct scope whose selection is SQL over `cards` (`did` or `odid` in the
scope); measured 4-11 ms per popup on the tablet. `guiBrowse` opens the card browser through its
`search_query` + `all_decks` extras (the `anki://` deep link keeps the last-chosen deck and can hide
the note). Media goes through the module's own `AnkiMediaProvider` (one cache directory), not
upstream's FileProvider. Without AnkiDroid or its permission every call answers a readable error
(Yomitan shows "Anki error: ..."; the popup hides the add buttons) and `AnkiAccess.refusals` reports it.

## 3.3 Audio

Yomitan's sources (`media/audio-downloader.js:49-59`): jpod101 (URL only), language-pod-101 (POST
then HTML), jisho (HTML), lingua-libre and wiktionary (CORS-friendly), text-to-speech (plays
through `speechSynthesis` only, never reaches Anki), custom and custom-json. All work once the
network routing above exists. **Local audio:** the community `android.db` (SQLite:
`entries(expression, reading, source, speaker, display, file)`, `android(file, source, data)`),
served under AnkiConnect Android's scheme so desktop backups work unchanged:
`GET /localaudio/get/?term=&reading=` returns custom-json with
`http://localhost:8765/localaudio/<source>/<file>` URLs (ranking in
`../refs/Hoshi-Reader/Core/LocalFileServer.swift:193-310`, GPL-3.0). The file is several GB and
picked through SAF; opening SQLite on it needs a real path (copy, or a `/proc/self/fd` read-only
open to test). **TTS fallback:** a custom source `http://localhost:8765/tts/get/?term={reading}`
answered with `TextToSpeech.synthesizeToFile`, last in the list, so it plays and reaches cards.

**As built (2026-09-27, `jp-yomitan/.../audio/`):**
- **Ruling: the picked `android.db` is copied into app storage** (`noBackupFilesDir`, with progress
  in `LocalAudio.status`). Opening it in place is impossible: the picker's descriptor links to the
  FUSE path (`/mnt/user/0/emulated/0/Download/...`), and re-opening `/proc/self/fd/N` is refused
  with EACCES for any open, plain Java included, so neither the bundled nor the framework SQLite can
  use it (tablet, 2026-09-27). Cost: the file's size again in storage (several GB); the settings
  screen should say so and that the original can then be deleted. The copy is a cancellable job in
  `LocalAudio`'s own scope, not WorkManager (a worker would need a foreground notification to
  outlive the app, for a one-time copy): a new pick or a removal cancels it, a failed, cancelled or
  short copy deletes its `.part` file, and a copy the process did not finish starts again on the
  next audio lookup.
- Ranking as Hoshi-Reader, without its mp3-only filter (WebView plays ogg/opus/m4a too).
- Text-to-speech: Google TTS's `ja-JP` voice on the tablet, WAV (about 70 KB per word). WebView has
  no `speechSynthesis` at all (`typeof speechSynthesis` is `undefined`), so Yomitan's own TTS source
  never works in the app. Without a Japanese voice the route answers 404 and Yomitan moves on.
- Yomitan's Japanese defaults (jpod101, language-pod-101, jisho) play and download through the
  network routing (checked on the tablet); `YomitanAudioSources` puts local audio first and TTS
  after the defaults.

## 3.4 The popup, search, "Look up"

- **Selection bug** (owner: a long-press selects one kanji, Firefox selects the word). Upstream's
  native mode is one selectable `TextView` per chunk (`NovelTextViewport.kt:1337-1358`); WebView
  mode builds its page in `web/NovelWebDocument.kt:74-97` with no `lang`. The tablet's system ICU
  and WebView's ICU both carry the Japanese dictionary (checked in the files), and the system
  TextClassifier is Samsung's smart-suggestions service with smart selection on, which both
  TextView and Chromium consult after the word break. Likely cause (about 60%, to confirm on the
  tablet with `TextClassifier.NO_OP`): Samsung's `suggestSelection` shrinks the word. Fix: a fork
  `TextClassifier` set on the chunk TextViews (`NovelTextViewport.kt:~1345`) and the WebView
  (`NovelWebViewport.kt:~203`) whose `suggestSelection` returns the Japanese word (ICU word
  instance for `Locale.JAPANESE`, Yomitan's longest match when warm) and whose `classifyText`
  passes through but puts "Look up" first. Also `lang="ja"` / `setTextLocale(Locale.JAPANESE)` for
  Japanese novels, so an English-locale device never draws Chinese glyph forms.
- **"Look up in Reikai JP"** in every app's selection menu: an `ACTION_PROCESS_TEXT` activity (the
  reader never overrides its toolbars, so it shows there too); disabled as a component while
  lookup is off.
- **The popup:** Yomitan's own results page in a sheet the app places (the spike's in-page popup
  opened off screen on the phone). Bottom sheet, drag to resize, swipe down to close, width capped
  on tablets; one popup for every surface.
- **Search screen:** Yomitan's `search.html` (needs only the pictures fix and a `query`
  parameter).

**As built (2026-09-28, `jp-yomitan/.../popup/`, `.../text/`, `app/.../jp/reikai/reader/` and `lookup/`):**
- **The selection bug's cause is not Samsung's classifier:** with `TextClassifier.NO_OP` on the reader's
  views (a debug-only `<external files>/jp-selection` switch) presses select exactly what they select
  with Samsung's. Android's `Editor.selectCurrentWord` starts from the character boundary nearest the
  finger, and in Japanese every word boundary lies between two letters, so a press on the left half of
  a word's first character or the right half of its last selects one character (the right half of
  "り" in 繋がり selects the "が" after it). Blink selects one character on any press and leaves the
  word to smart selection. ICU's dictionary also splits kanji from okurigana (島流|し, 恐れ|て).
  Firefox, with its own segmenter, selects 手助け, 繋がり, 伯爵.
- **Fix:** `JpTextClassifier` on each chunk TextView and the WebView (Android 9+) answers Japanese
  text itself (`JapaneseText.selectWord`): ICU's Japanese word around the character under the finger
  (a touch listener remembers the press; the TextView's text window is matched against the chunk,
  `TextWindow`; the WebView's page is asked whether the finger was left of Chromium's one-character
  selection), extended by the dictionary's longest match when the engine is already running (150 ms
  cap): okurigana and inflections, and back over a kana ending ICU split from its kanji stem, never
  shorter than ICU's word. Chromium drops a suggestion that does not contain its own selection, so in
  the WebView a press on the right half of "り" selects 繋がりが (the lookup still finds 繋がり).
  Other scripts go to the system classifier. Tablet, native mode, presses on character halves: system
  繋, が, 島, て, 手; fork 繋がり, 繋がり, 島流し, 恐れて, 手助け. WebView mode: system one character
  every time; fork 繋がり, 繋がりが, 島流し, て、, 手助け, 手助けし, 魔物 (no dictionary).
- Japanese glyphs: `setTextLocale(Locale.JAPANESE)` on the chunks and `<html lang="ja">` for a novel
  whose source language is `ja` or whose chapter has kana (the tablet's en-US locale drew ？ in its
  Chinese form before).
- **The popup is `popup.html` driven through browser history**, not `search.html` (its search box and
  header are too much for a popup) and not Yomitan's cross-frame messages (a host page with Yomitan's
  frontend and an iframe: two documents, and Yomitan's internals). The display reads a lookup from its
  URL (`query`, and `full` + `offset`, which Anki's Sentence field falls back to) and the rest
  (sentence, `url`, `documentTitle`, `pageTheme`) from `history.state`, which DisplayHistory reads on
  `popstate`; `assets/jp-reikai/popup-host.js` swaps lookups with `history.replaceState` and a
  `popstate` event. Standalone, the page loads its settings only during its first lookup (after
  deciding whether any dictionary is enabled), so it starts with an empty lookup and is ready after
  it; its theme is decided then too, so a lookup in the other theme reloads it. Yomitan's default
  "site" popup theme then follows the reader's page. **3.6 tripwire fingerprints** this relies on:
  DisplayHistory's `history.state` `{id, state}` and `_onPopState`; `_onStateChanged` reading
  `location.search`; `_setTheme` reading `_history._current.state.pageTheme`; ids
  `#dictionary-entries`, `#no-results`, `#no-dictionaries`, `#close-button`. If they break, the
  sentence still reaches Anki through `full` and `offset`.
- A word tapped inside the results opens Yomitan's nested popup inside the sheet with its defaults
  (`popupNestingMaxDepth` 10); navigating within the sheet instead is Yomitan's
  `scanning.enablePopupSearch` (off by default), for the settings screen (3.5) to offer.
- **Speed (tablet, warm engine):** the lookup runs while the toolbar shows (`classifyText` preloads
  it into the closed sheet) and a press on "Look up" is taken from the selection's own event
  (`SelectionEvent.ACTION_SMART_SHARE`), 50-70 ms before the action's broadcast arrives. Toolbar tap
  to the frame showing the results: native mode n=12, min 79, median 98, p95 102, max 131 ms;
  WebView mode (broadcast path) n=6, 93-155 ms; before the preload 170-230 ms. Cold: "Look up in
  Reikai JP" with the app stopped takes 1.6 s to the activity's first frame and 1.65-1.8 s more to
  results (engine 1.05 s, then the popup); a reader's first lookup 3.5 s after its chapter opened was
  already warm. The sheet parks transparent at full size between lookups (resizing or detaching a
  WebView costs frames); one popup moves between screens (`MutableContextWrapper`) and closes 60 s
  after the last one lets it go.
- **Anki:** on a refusal the sheet offers "Allow Reikai JP to add cards to AnkiDroid" (the runtime
  permission; "Open settings" once denied for good) and says once per run when AnkiDroid is missing.
  Tablet: a Lapis card added from the sheet had Sentence "おそらく、逃亡を<b>手助け</b>したのも狼面衆だろう
  とのことだったが……。" and MiscInfo "第一部 - 第30話：... - 田舎の悪役貴族、...".
- **Surfaces:** "Look up in Reikai JP" (`LookupActivity`, `PROCESS_TEXT`, a translucent sheet over the
  other app; its component follows the lookup switch through `JpLookup`); the "Dictionary" launcher
  shortcut and Yomitan's links to its search page open `DictionaryActivity` (`search.html`, keyboard
  opened on the search box instead of moving Yomitan's layout); both say when lookup is off and offer
  to turn it on. The activities are declared in `jp-yomitan`'s manifest (their classes are the app's),
  so upstream's manifest has no seam; the shortcut is a seam at the top of `app/src/main/shortcuts.xml`
  because Samsung's launcher shows only an app's first four.

## 3.5 Settings

A fork "Japanese" entry in Settings (seams: `SettingsMainScreen.kt:~250` item,
`SettingsSearchScreen.kt:~327` index), a `SearchableSettings` screen like
`SettingsNovelReaderScreen.kt:56-73`: lookup on/off, dictionaries, Anki permission, local audio,
and "All Yomitan settings" (settings.html in an activity). Needs `onShowFileChooser` (dictionary
zips, settings JSON) and a blob-download bridge (the stand-in patches blob-download anchors
`backup-controller.js:166-195`, sends the blob in slices, the app saves through SAF). Desktop
backup: `{version:0, ..., options}`, Yomitan migrates it itself (`backup-controller.js:404-458`);
dictionaries are re-imported from their zips (a Dexie export of 300 MB is too heavy for a phone).

**As built (2026-09-28, `app/.../jp/reikai/settings/`, `jp-yomitan/.../settings/`):**
- **Settings, Japanese** (`SettingsJapaneseScreen`, `JapaneseSettingsViewModel`): lookup switch (off
  mid-run says the memory is freed at the next start: WebView's renderer keeps it), "Open
  dictionary"; installed dictionaries (`getDictionaryInfo`) and "Get recommended dictionaries";
  AnkiDroid (install link, Allow with the runtime request, "Open settings" once denied for good);
  cards; local audio (picker, copy progress, size, remove) and text-to-speech (a switch over
  `YomitanAudioSources`, the voice's state); "Import your desktop Yomitan settings" and "All Yomitan
  settings". The screen holds the engine only while visible and reads everything afresh on each
  return. While lookup is off every other row is greyed out (upstream's rows hide when disabled, so
  the screen draws its own) with one line saying why. Settings search indexes the rows without
  starting anything.
- **Yomitan's own pages** open in `YomitanSettingsActivity` (settings.html, and info or legal pages
  Yomitan links to). There is no deep link in Yomitan's settings (no hash handling; `<body hidden>`
  until prepared): the activity waits for the body to show, scrolls to the section's `<h2 id>` and,
  for "Get recommended dictionaries", clicks Yomitan's own `[data-modal-action="show,recommended-dictionaries"]`,
  whose list (`data/recommended-dictionaries.json`: Jitendex, JMnedict, KANJIDIC, three frequency
  lists) imports through "Download" and the engine's proxy with Yomitan's progress bar. Yomitan's
  "no dictionaries" links (popup, search) open the settings screen, which opens that list when none
  is installed. File inputs open the system picker (`*/*`: a backup copied to a tablet is not always
  typed as JSON); a blob download (`backup-controller.js` `_saveBlob`) is sent by the stand-in in
  512 KB base64 slices (`save` requests, settings pages only) to `YomitanSaves`, then saved through
  the system's "save as".
- **Ruling: "Set up cards for Lapis" writes the current profile's first word card format only**
  (deck, model, fields; Anki on), with Lapis's README markers; MainDefinition is
  `{single-glossary-<kebab title>}` of Jitendex, else JMdict, else the first enabled word dictionary
  (`{glossary-first}` with none). Offered only when no card format has a note type and AnkiDroid has
  one named Lapis. Cost if wrong: other profiles keep no card format.
- **Ruling: Clear WebView data keeps the engine origin** by deleting every other origin through
  `WebStorage.getOrigins`/`deleteOrigin` and all cookies, and no longer deletes `app_webview/`
  (deleting Chromium's files by hand depends on its layout and would take the dictionaries with it).
  Cost if wrong: storage WebView does not list per origin is no longer cleared.
- Tripwire: display history and theme, `_saveBlob`, the recommended list and the page markup both
  hosts find are fingerprinted (`page-elements` surface).
- **Not yet proven on a device** (the tablet was not connected when this was built): the screen,
  the section scroll and recommended list, the file picker, the export save, the Lapis card, local
  audio, Clear WebView data keeping the dictionaries. Mobile defaults for Yomitan's popup
  (`scanning.enablePopupSearch` and others, set once on a fresh profile) wait for that check.

## 3.6 Automatic updates

`scripts/fork/yomitan_bump.py` (check, vendor, verify) and `.github/workflows/fork-yomitan-update.yml`
weekly (not Monday): `releases/latest` (promoted releases only), a 7-day soak, download
`yomitan-firefox.zip` and check its `digest`, re-vendor, then a **static tripwire** in Python
(`chrome.X.Y` chains without JSDoc type lines, AnkiConnect action names, audio source types,
manifest permissions, IndexedDB schema versions, fingerprints of the sensitive call sites:
`new Worker(`, `new SharedWorker(`, `'serviceWorker' in navigator`, `application.js:193-222`),
and a **smoke test** in the runner's Chrome (`playwright-core`, pinned): the release served at the
engine origin with the stand-in and a JS mock of the hub, a tiny Yomitan test dictionary imported
(`test/data/dictionaries/valid-dictionary1`, GPL, credited), one `termsFind` checked, the
tripwire log empty. Success commits `chore(yomitan): update to <tag>` and pushes with
`GITHUB_TOKEN`; the daily release workflow ships it. Failure pushes nothing (D-015). Yomitan's own
vitest suites run against its source tree with their own mocks and test nothing of ours, so they
are not run (architecture.md's "golden-file tests through the stand-in" is corrected). About
85-100 CI minutes a year. Residual risk: a release that changes behaviour without new API names
passes; a new IndexedDB version cannot be rolled back.
