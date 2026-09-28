# Phase 4 design: the Japanese reading mode (September 2026)

Built as one item (D-028). Evidence came from four scouting passes: upstream's novel reader, ttu /
Hoshi / chimahon, Tsundoku's reader, and Yomitan's text scanner in a chapter page. This file is the
shared plan and the contract between the page script and the Kotlin viewport; as-built notes are
added under each part as it lands.

## Borrowed code and licences

- **ttu-ebook-reader** (BSD-3-Clause, "ッツ Reader Authors"): the character-count rule, the
  furigana modes, the statistics model and export format. Copied code keeps its notice; the notice
  also goes in `LICENSES/` and About's open-source list.
- **chimahon** (GPL-3.0) and **Hoshi-Reader** (GPL-3.0-or-later): the paging technique in an
  Android WebView (body as the multicol container, `column-width` = page height in vertical text,
  pages stepped by `scrollTop`; `scrollLeft` in horizontal text), fixes for Chromium (root scroll
  locked at 0, snap back to a page), the bottom-overlap spacer in vertical text. Copied code keeps
  its copyright header.
- **Tsundoku** (Apache-2.0, may be used in GPL-3.0 code): the translation engines and the EPUB
  writer, with the Apache notice kept.

## What upstream gives us (facts)

- The reader picks a viewport in `NovelReaderProvider.createViewport` (called from `attach`,
  synchronously); a novel viewport implements `ReaderViewport` and `TextViewport` (`load`,
  `applySettings`, `setAutoScroll`, `window: ChapterWindow`, `readAloud: ReadAloudSurface`,
  `setObscured`). Viewports are Android Views in `binding.viewerContainer`.
- Progress persists only as `novel_chapters.last_text_progress` (hundredths of a percent, fed by
  whole percents). The callbacks `reportProgress`, `saveProgress`, `reportTopLine`,
  `reportVisibleChapter`, `reportFitsOnScreen`, `reportChapterEndSeen`, `toggleMenu` and the
  engine's `nextChapter/previousChapter` keep history, "read" and tracking working.
- `NovelChapterTextLoader.load` sanitises for `TEXT_VIEW` or `WEB_VIEW` from the *global*
  rendering mode (`NovelChapterTextLoader.kt:104-108`); the Japanese reader needs the WebView form.
- All novel text settings are global `ln_reader_*` preferences; only orientation is per novel
  (`novels.viewer_flags`, mask `0x38`; bits `0x07` and `0x40`+ unused for novels).
- The source's language is known from `NovelSource.langCode()`; `JpReaderSession` learns it only
  after the first chapter loads, which is after `createViewport`.

## Rulings (low-stakes choices made by the agent)

Each: what - why - cost if wrong.

1. **Its own viewport**, `jp.reikai.reader.page.JpPageViewport`, one chapter per document; the page
   turn past a chapter's last page steps the chapter. Seamless chapters are off in this reader -
   paging and vertical text do not fit upstream's scroll-window model - the owner sees a chapter
   load between chapters (upstream's WebView mode without seamless chapters behaves the same).
2. **Chapter documents at `https://chapter.reikai.invalid/`** (RFC 6761), served by the viewport,
   with a Content-Security-Policy that runs only our script and Yomitan's modules - Yomitan's
   content-role stand-in needs a fixed https origin, and chapter HTML must never run its own
   scripts next to the stand-in - none.
3. **Per-novel choice in `viewer_flags` bits `0x300`** (0 follow the default, 1 Japanese reader,
   2 standard reader), written through a fork interactor with the same mask pattern as
   orientation - backups and migrations already carry the column - an upstream use of those bits
   would collide (the sync would show it).
4. **Default:** the Japanese reader for a novel whose source language is `ja`; for a source whose
   language is not specific (multi, unknown, local), when the first chapter looks Japanese - the
   menu switch covers the rest - none.
5. **Shared settings:** theme colours, font size, line height and margins are upstream's (so its
   quick text-size and theme dialogs keep working); the Japanese reader adds its own `jp_reader_*`
   settings: text direction, pages or scrolling, furigana, Japanese font, what a tap does, status
   bar. Cost if wrong: switching between an English and a Japanese novel may want a size change.
6. **Fonts:** the system's Mincho (Noto Serif CJK, `serif`) and Gothic (Noto Sans CJK,
   `sans-serif`) with `lang="ja"`, plus fonts installed through upstream's font manager, served
   from the chapter origin rather than as `data:` URIs - nothing bundled, no megabyte copies - a
   device without the serif CJK font falls back to Gothic.
7. **Position by character** (ttu's rule), exact to the character: kept across font and layout
   changes in the page, persisted per chapter in the fork's own database, and reported to upstream
   as a percent too - upstream keeps only 101 positions per chapter - none.
8. **Pages by default, vertical text** (D-026); page turns are instant, as in ttu and Hoshi - a
   sliding animation costs frames on long chapters - the owner may miss the slide.
9. **Taps (D-029):** a tap on a character looks it up; a tap elsewhere (margins, blank space)
   opens the menu; swipes and volume keys turn pages. Setting "Tap on text": Look up (default) or
   Turn pages (upstream's tap zones, mirrored for vertical text; long-press still looks up). A tap
   on hidden furigana reveals it before any lookup.
10. **Furigana** (ttu's modes): Show (default), Dimmed (tap shows), Hidden (tap shows), Tap to
    toggle, Never - ttu's names and behaviour.
11. **Japanese line breaking:** `line-break: strict`, `text-spacing-trim` where Chromium supports it,
    `text-orientation: mixed`, and runs of one or two half-width digits or `!!`, `!?`, `?!` set
    upright in vertical text (tate-chu-yoko) - as printed books do - a three-digit number stays
    sideways.
12. **Statistics** (ttu's model): one row per novel title and day: characters read, reading time
    (s), last/min/max speed (characters per hour). Time counts while the reader is on screen and
    touched or turned within the last 5 minutes (idle time is taken back); jumps of 2700 or more
    characters are not counted as read. Export is ttu's per-title JSON in a zip - the owner can
    import it into ttu - none.
13. **Status bar (4.5):** a fork overlay in every novel reader: clock, battery, chapter, progress,
    and in the Japanese reader characters read and reading speed. On by default in the Japanese
    reader, off in the standard one - none.
14. **Short chapters read (4.5):** a chapter that fits on one screen (or one page) is marked read
    when it opens; on by default, switch in the reader settings - none.
15. **Translation (4.5):** in the reader menu, "Translate chapter" shows a translation of the
    chapter in place (same button returns to the original), cached per chapter. Engines: Google
    (no key), DeepL (key), and any OpenAI-compatible service (key, address, model: OpenAI, Gemini,
    DeepSeek, Ollama, OpenRouter). Nothing is sent until the owner taps it. Keys stay in a fork
    preference left out of backups - an engine the owner wants may be missing.
16. **EPUB export (4.5):** "Export as EPUB" in the novel screen's menu writes the downloaded
    chapters, cover and details to a file the owner picks - chapters not downloaded are skipped
    and the owner is told how many.

## Translation and EPUB export (4.5)

From Tsundoku only the engines' requests and the EPUB writer are borrowed (Tsundoku's queue,
store and job are tied to its download model; its chapter translator glues ruby readings into
the text and drops images, its EPUB writer emits HTML, not XHTML, and hard-codes `lang="en"`).

- **Translation** (`jp.reikai.translate`): `ChapterTranslator` parses the chapter the pipeline
  produced, drops `rt`/`rp`, sends text blocks (`p`, headings, `li`, `blockquote`, text divs) in
  batches and writes each result back into its element, so pictures stay; the counts must match.
  Engines: Google's `client=gtx` endpoint (no key; a failure is an error, never the source text
  passed off as a translation), DeepL (repeated `text`, `api-free` for `:fx` keys, EN-US for
  English), one OpenAI-compatible engine with presets (OpenAI, Gemini's `/v1beta/openai`,
  DeepSeek, OpenRouter, Ollama `/v1`). Cache: `filesDir/jp_translations/<chapterId>/<lang>-<engine>-<hash>.html`.
  Seams: `NovelReaderViewModel.loadChapterHtml` (swap in the translation when that chapter is
  shown translated) and one line in `ReaderTopBar`'s overflow for the fork's reader menu (which
  also carries the Japanese/standard reader switch). Keys through `Preference.privateKey`, so
  backups leave them out.
  Rulings: target language is the device's (English when it is Japanese) - cost: one setting
  change; "show translated" lasts while the reader is open - immersion readers peek, not stay -
  cost: tapping again next time.
- **EPUB export** (`jp.reikai.export`): a `CoroutineWorker` with a notification on upstream's
  common channel writes EPUB 3 (well-formed XHTML, the novel's language, a cover page, an
  identifier stable across exports) to a file picked with `CreateDocument`. Chapters in source
  order, only downloaded ones, passed through the same content pipeline the reader uses (so
  replacement rules apply), `data:` pictures turned into files, the cover from the cover cache.
  Seam: one line in `EntryToolbar`'s overflow. Rulings: a Japanese novel's EPUB is vertical and
  right-to-left (`page-progression-direction="rtl"`), as printed; a merged novel exports the
  chapters its screen lists - cost: a reader app that ignores the book's direction shows it anyway.

## The page contract (jp-reader.js ↔ JpPageViewport)

Assets in `app/src/main/assets/jp-reader/` (fork-owned): `jp-reader.css`, `jp-reader.js`, served
at `https://chapter.reikai.invalid/jp-reader/…`. The document is served at
`https://chapter.reikai.invalid/chapter/<documentId>` with

```html
<html lang="ja" class="jp-vertical|jp-horizontal jp-paged|jp-scroll jp-furi-<mode>" style="--jp-…">
<head><link rel="stylesheet" href="/jp-reader/jp-reader.css"><style id="jp-font-face">…</style>
<script type="application/json" id="jp-init">{…}</script></head>
<body><main id="jp-chapter"><h1 class="jp-title">…</h1> …sanitised chapter HTML… </main>
<script src="/jp-reader/jp-reader.js"></script></body></html>
```

`jp-init`: `{ "chapterId": <long>, "charOffset": <int|null>, "fraction": <0..1>, "settings": {…} }`.
The start position is `charOffset` when known, else `fraction` of the chapter's characters.

`settings`: `{ writing: "vertical"|"horizontal", layout: "paged"|"scroll",
furigana: "show"|"partial"|"full"|"toggle"|"hide", tapMode: "lookup"|"zones",
tapZones: [[x0,y0,x1,y1,"menu"|"back"|"forward"], …] (fractions of the viewport, already mirrored
for vertical text), fontFamily: "<css list>", fontSize: <px>, lineHeight: <number>,
margins: {top,right,bottom,left} (px), insets: {top,bottom} (px: the cutout at the top; at the bottom what the app keeps over the page, its status bar or progress readout),
colors: {background, text, hint}, textIndent: <em>, justify: <bool>, invertSwipe: <bool> }`.

**Page → app**, one `WebMessageListener` named `jpReader` restricted to the chapter origin
(`postMessage(JSON.stringify(msg))`), messages `{t, …}`:

| `t` | fields | when |
|---|---|---|
| `ready` | `pos` | laid out and landed (fonts loaded) |
| `pos` | `pos` | after every page turn or scroll settle, and after a re-layout |
| `tap` | `x`, `y` (0..1), `action` (`menu`/`back`/`forward`/`none`) | a tap that is not a lookup and not a furigana reveal |
| `edge` | `forward` (bool) | a page turn asked past the first or last page |
| `touch` | - | any touch start (idle timer, engine warm-up) |

`pos` = `{ charOffset, anchor, chars, fraction, page, pages, fits, endSeen }`: characters before the
first visible character (ttu's rule); the anchor, the character the reader means to be at (the first
visible one after their own last move, kept through re-layouts; the app stores it, so a document
laid out another way lands on the page holding it instead of slipping back a page); the chapter's
total, the ratio of `charOffset` to it, the 1-based page and page count (in scroll mode screens),
whether the whole chapter fits on one page, whether its end is on screen. An `edge` always comes
after a `pos` for the place it was asked from.

**App → page**, `evaluateJavascript("JpReader.<fn>(…)")`:

| call | does |
|---|---|
| `applySettings(settings)` | re-lays out, keeping `charOffset` |
| `turn(dir)` | `+1` next page (or a screen in scroll mode), `-1` previous; past an end posts `edge` |
| `seekFraction(f)`, `seekChar(n)` | move to a position (pages snap) |
| `autoScroll(px)` | scroll mode only; `0` stops |
| `paragraphs()` | array of strings, upstream's read-aloud rule (non-blank lines as shown, whitespace collapsed, trimmed, U+FFFC removed, ruby readings left out) |
| `firstVisibleParagraph(top, bottom)` | index, clear of the chrome's pixels |
| `highlight(i, start, end)` | marks paragraph `i` (a range of it, or all for `-1`), turns to it when off screen; `highlight(-1)` clears |
| `state()` | the current `pos` |

**Hooks for lookup (4.3):** `JpReader.onTextTap = (x, y) => boolean` (client px), set by the
lookup script; `jp-reader.js` calls it for a tap on a character when `tapMode` is `lookup` (for a
tap on a shown reading, at the first character of its word) and posts `tap` only when it returns
false or is unset. `JpReader.charRangeAt(x, y)` answers whether a point is on a character.

**Character counting** is ttu's exactly: text of text nodes outside `rt` and outside
`[hidden]`/`[aria-hidden]` subtrees, with
`/[^0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu` removed,
counted in code points; an `img` whose class contains `gaiji` counts 1.

## Budgets

Chapter open not slower than upstream's WebView mode on the tablet; a page turn inside one frame;
re-layout after a settings change under 300 ms for a 20 000-character chapter on the tablet; tap
to popup as Phase 3 (p95 under 150-200 ms).

## As built: 4.1 and 4.2 (Kotlin)

Fork code in `app/src/main/java/jp/reikai/reader/page/` and `jp/reikai/data/`; strings in
`jp-yomitan/src/main/res/values/jp_reader_strings.xml`.

- **Viewport** `JpPageViewport`: one chapter per document at
  `https://chapter.reikai.invalid/chapter/<chapterId>-<n>`, served with the page assets and the
  reader's added fonts (`/font/<file>`, only those the document declares) by `JpPageClient`, which
  hands pictures and links to upstream's `NovelChapterNavigationClient` and refuses every other
  request. CSP header: scripts from `'self'` and Yomitan's origin only, inline styles allowed, no
  eval. The chapter's markup is re-parsed and loses every script, handler and embedding element
  (`JpPageDocument.cleanChapter`), whatever "keep embedded scripts" says. Page messages come through
  `addWebMessageListener("jpReader")`, main frame of the chapter origin only; `pos` before `ready`
  of the current document is ignored, and a `chapterId` field, if the page ever sends one, must
  match.
- **Upstream's callbacks:** every `pos` goes to `saveProgress` as a whole percent (0 when the
  chapter fits on one page, 100 once its end is on screen, else the character share capped at 99,
  as upstream's scroll reports), `reportTopLine(null)` (so a switch back lands at the percent),
  `reportFitsOnScreen` on change, `reportChapterEndSeen` once. `edge` steps the chapter through the
  engine (one step until the reader moves again); a step back opens the previous chapter on its last
  page. `tap`: with "Tap on text: Look up" it opens the menu; with "Turn pages" upstream's tap zones
  decide, mirrored left to right for vertical text (`JpTapLayout`). Volume keys turn pages.
- **Position** (ruling 7): `JpChapterPositions` over `JpReaderDatabase` (`jp_reader.db`, plain SQL
  on the bundled SQLite; version 1 = `chapter_position(chapter_id, char_offset, chars, percent,
  updated_at)`; 4.4 adds version 2). `char_offset` holds the page's anchor rather than its first
  character (seen on the tablet: two rotations slipped the reader back a page). A chapter lands at the stored character when the stored
  `percent` equals the percent upstream opens it at, else at upstream's percent (so a place the
  standard reader, a mark-as-read or another device moved since is not trusted). Incognito stores
  nothing.
- **Which reader** (`JpReaderModes`, decided once per reading session, keyed by the novel the
  session opened): the novel's choice in `viewer_flags` bits `0x300` (`JpReaderChoice`, written by
  `SetJpReaderChoice`), else Japanese for a source whose language is `ja`, standard for another
  specific language, and for a source that is not one language (`all`, unknown, local) whatever
  `JapaneseText.looksJapanese` says of the first chapter loaded. The source language is read
  without loading plugins (the seen-source record, else an app source). The viewport, the chapter
  pipeline and the model's window read the same answer.
- **Why a holder** (`JpReaderSwitch`): `createViewport` runs synchronously in `onCreate`, before the
  novel row is read, so the provider gets a placeholder that builds the real viewport (the
  Japanese one, or upstream's own through the provider's `createViewport`) once the answer is
  known: from the row a few milliseconds later off the main thread, or from the first chapter's
  text. A rebuild around a live session builds at once. The built view goes into the reader's
  container beside the placeholder, not inside it, because upstream's viewports lift the
  container's focus block from their direct parent (text selection).
- **Switching:** "Switch to Japanese reader" / "Switch to standard reader" in the top bar's
  overflow menu, and "Reader: Japanese / Standard" under "For this series" in the settings sheet,
  followed there (Japanese reader only) by Reading (Pages / Scrolling), Text direction, Furigana,
  Japanese font and Tap on text. A switch updates the session at once, bumps a counter the chapter
  pipeline watches (so the chapter is prepared again for the other reader), writes the flags and
  rebuilds the Activity, as upstream's rendering-mode change does. The first time the Japanese
  reader lands in vertical text, a one-time dialog (`jp_reader_intro_shown`) offers horizontal.
- **Seams (9 in 5 files):** `NovelReaderProvider.attach` (the holder), `NovelChapterTextLoader`
  (the WebView form for the Japanese reader; the switch counter in `settingsChanged` and its
  snapshot), `NovelReaderViewModel` (binding the session's loader to its novel; `windowedReading`
  off for the Japanese reader, which is how seamless chapters stay off: the model then behaves as
  with the setting off), `NovelReaderSettingsPages` (the rows), `ReaderTopBar` (the menu item).
- **Hooks for 4.3:** `JpPageViewport.listeners` (`onDocumentStart(webView, chapterId)` before each
  load, `onReady`, `onTouch`) and `requestInterceptor` (answers the page's requests first, e.g.
  Yomitan's origin); `JpReaderSession.onPageViewport` receives each new page viewport.
- **Checked on the JVM only:** flag bits, the decision table, settings JSON and zone mirroring,
  message parsing and the percent rule, the document's shape and cleaning, the database, and the
  session registry (77 tests). Not yet seen on a device: the page in the reader, landing, paging and
  scrolling in both directions, switching, the intro dialog, read-aloud and fonts.

## As built: 4.3 (tap to look up)

- **Scanner in the page:** with lookup on, the document loads `jp-yomitan/.../jp-reikai/reader-scan.js`
  as a module from Yomitan's origin after `jp-reader.js`. It runs Yomitan's `Application.main`, a
  `TextSourceGenerator` and a `TextScanner` with Yomitan's scanning and sentence settings applied as
  Frontend applies them, never enabled (its own listeners never run), and searches through the
  public `search()`. A tap inside a word searches from the word's start: the browser's Japanese
  segmenter's word, or a kanji stem when the tapped piece is okurigana or a lone kanji the
  segmenter split off (`JapaneseText.selectWord`'s rule), else the tapped character. The word is
  selected (Yomitan's "select matched text"), which is the highlight; programmatic, so no handles or
  toolbar. Messages to the app through `reikaiReader` (chapter origin, main frame): `ready`, `wait`,
  `found {type, query, sentence {text, offset}, rects, writingMode, ms}`, `empty`, `error`; from
  the app `__reikaiReader.clear()`, `setContext({url, title})`, `start()`. A tap before the engine
  runs posts `wait`, is kept 10 s, and is searched when the backend's `applicationBackendReady`
  reaches the page.
- **App side:** `JpPageLookup` (per page viewport) joins the page to the hub
  (`YomitanEngine.attachContent`: the stand-in in content mode for the chapter origin, a READER hub
  view, no WebViewClient takeover; Yomitan's files through the viewport's `requestInterceptor`)
  only while lookup is on, and the viewport adds the script tag only then. `found` goes to
  `JpReaderSession`'s sheet with the word's screen rows (CSS px times density) for its placement;
  the sheet's close clears the highlight; `wait` starts the engine at once. The Japanese page also
  gets the fork's text classifier, so a long press selects the word and offers "Look up"; the
  press-before check is vertical-aware (above the selected character in vertical text).
- **Cover on cards:** Yomitan's `{screenshot}` asks the tab the lookup came from (the popup's own)
  to hide its popups, which `popup-host.js` answers unless a Frontend in the popup says
  `frontendReady`, then calls `captureVisibleTab`, which the backend stand-in turns into a hub
  `capture` request naming that tab (backend only). The hub asks the popup's page listener for the
  lookup's `LookupPicture`: `NovelCoverPicture`, the novel's cover through Coil (custom cover, cover
  cache, source) as a JPEG of at most 800 px, none for the plugins' "no cover" placeholder URLs.
  Without a picture the card is added with the field empty and `popup-host.js` closes Yomitan's
  notice about it. "Set up cards for Lapis" maps Picture to `{screenshot}`; a profile set up before
  gets it by running the setup again.
- **Checked:** smoke test (headless Chromium: chapter page as the app builds it, taps, whole words
  from inside words and readings, the highlight, a card with and without a cover), page tests, JVM
  tests (hub capture rights, message parsing, the document's script tag, Lapis fields). Tablet
  (SM-X520, Jitendex, vertical paged, 2026-09-28): taps on 15 words' second characters found the
  whole words; tap to painted results n=14 min 74, median 120, p95 and max 166 ms (the scanner's
  search plus the popup's own); a first tap 1.3 s after opening the reader, engine not started:
  988 ms to painted results; the sheet at the bottom for a word high on the page, at the top for one
  low on it; highlight cleared on close; a Lapis card in AnkiDroid with the bold word in its
  sentence, chapter and novel in MiscInfo and the cover (533 x 800 JPEG) in Picture; without a
  cover, the card added with Picture empty and no notice; long press on 向 selected 傾向 and "Look
  up" opened it (55 ms, preloaded); lookup off: no engine page, a tap on a word opened the menu.

## As built: 4.4, status bar, short chapters

Fork code in `app/src/main/java/jp/reikai/stats/` (statistics), `jp/reikai/reader/JpStatusBar.kt`
and `jp/reikai/settings/JpStatisticsScreen.kt`; strings in
`jp-yomitan/src/main/res/values/jp_stats_strings.xml`.

- **Statistics (ruling 12):** `jp_reader.db` version 2 adds `reading_statistic`: ttu's row field for
  field (`TtuStatistic`: title, dateKey, charactersRead, readingTime in seconds, min, alt-min, last
  and max speed, lastStatisticModified; completedBook and completedData carried through, never set
  here), keyed by title and day as in ttu, plus the novel last read under that title for this app's
  lists. `JpReadingStatistics` (app-scoped) writes rows off the main thread and lays unwritten ones
  over what it reads (taking the unwritten ones before reading the database, so a flush between the
  two cannot hide a day: found in review, it would have let a rotation reset the day). ttu's update rule, export file name and folder name are ported; the JVM test
  holds them to ttu's own functions through a fixture that
  `scripts/fork/ttu-statistics/make-fixture.mjs` makes by running them. Found that way: a title's
  minimum speeds in its file name depend on the order of its days, so the export sorts by day first,
  as ttu's database returns them.
- **Tracker** (`JpReadingTracker`, one per reader Activity, fed by the viewport's
  `Listener.onPosition(PageReport)`): a tick a second while the Activity is resumed
  (`repeatOnLifecycle`), whole seconds counted and the remainder carried. The reader is active after
  a page touch, a lookup or the page moving; after 5 minutes without, the 5 minutes are taken back in
  one update (ttu's `elapsed - idleTime`) and time waits for the next activity. Characters are
  `charOffset` differences per page report rather than per tick (two page turns in one second are
  not a jump), forward only past the furthest character reached (back and forward again counts a
  page once); a move of 2700 or more either way counts nothing and reading goes on from where it
  landed. Leaving a chapter whose end was on screen, other than by a step back, counts the rest of
  its last page; the new chapter counts from its landing. A tick reaching back past midnight gives
  the earlier seconds to the day before (characters stay on the later day; ttu adds them to both).
  Rows go to the store every 10 s and on pause; a chapter whose source is in incognito counts
  nothing. The session's totals (the status bar's speed) are kept per reader model, so a rotation
  keeps them.
- **Statistics screen:** Settings, Japanese, Reading, "Reading statistics": today, last 7 days and
  all time (characters, time, characters an hour), the last 14 days as rows with a bar, each novel's
  totals, and "Export for ttu" (`CreateDocument`, `application/zip`): per title
  `<ttu-sanitized title>/statistics_1_6_…json` with the days as ttu's JSON, UTF-8 names flagged.
  ttu imports it from its book manager, "Import Backup".
- **Status bar (ruling 13):** `JpStatusBar`, one View drawing three prepared strings: clock and
  battery (⚡ while charging); the chapter, cut with an ellipsis and centred where it fits (Japanese
  glyph forms for a Japanese novel); `charOffset / chars`, the session's speed and the progress in
  the Japanese reader, the progress alone in the standard one. The session adds it to
  `android.R.id.content`, above the reader (it covers upstream's progress readout, the same number)
  and below the lookup sheet; it hides while the menu is open. The clock ticks on the minute and the
  battery comes from its sticky broadcast, both only while the bar shows and the reader is started
  (on the tablet the battery receiver is registered while reading, gone in the background). Colours are the reader
  theme's (text at 70 %), padding the visible system bars and side cutouts. Settings
  `jp_reader_status_bar` (on) and `jp_standard_status_bar` (off), switches at the end of the fork's
  rows under "For this series" for whichever reader is in use. The Japanese page keeps the bar's
  height in `insets.bottom` (`JpPageViewport.reservedBottomDp`, set before the first layout, so a
  change re-lays out in place), or with the bar off the height of upstream's readout when that is on
  (a 16 sp line and its outline). Horizontal scroll mode, whose text scrolls under the bottom, paints
  that strip in the page colour (`--jp-inset-bottom`). The standard reader keeps no space: there the
  bar is opt-in and text scrolls under it.
- **Short chapters (ruling 14):** one seam, in `NovelReaderViewModel.reportFitsOnScreen`, hands every
  report to `JpReaderHook.chapterFits` with a finisher calling upstream's own
  `persistProgress(id, 100)`, the path a forward step from a chapter that fits already takes
  (progress, read, trackers through `NovelChapterFinish`). The session marks the chapter being read
  (`viewModel.chapter`) once it has fitted for 1.5 s (pictures can make it grow), once per session.
  Setting `jp_short_chapters_read` (on), shared by both readers. Seams: 50 in 28 files.
- **Checked:** JVM tests for the port against ttu's code (20 update steps, 3 file-name sets, 9 folder
  names, the zip), the tracker (ticks, carried time, forward-only, jumps, idle, midnight both ways,
  chapter changes, incognito, pause, stored days, session carry-over), the summary and the database
  migration; breaking the alt-min rule or the forward-only mark fails them. Page tests pass. Tablet
  (SM-X520, Kakuyomu novel, vertical paged, 2026-09-28): ten page turns 16 s apart from chapter 4's
  start into chapter 5 (page states read with `JpReader.state()` over DevTools): stored 3,969
  characters (all 2,828 of chapter 4, its last 182 counted on leaving it, plus 1,141 into chapter 5)
  and 236 s (landing at 14:58:05 to the pause at 15:02:01), speed 60,545 = ceil(3600 × 3969 / 236);
  the screen showed the same; the exported zip held one file whose name ttu's own function gives for
  its JSON. Status bar at the bottom with the text columns ending above it (paged; vertical scroll, where
  the page kept 41 px: the 25 px bar and the 16 px margin), hidden under the open menu; with the bar off, upstream's
  "0% / 100%" readout and 21 px kept; standard reader: clock, battery, chapter, progress. A chapter of
  1,448 characters laid out to fit one page (tiny horizontal text set through DevTools) went from
  unread to read with progress 100 % 1.5 s later. `:app:assembleNightly` (R8) builds. Not checked:
  an import into ttu itself (the zip follows ttu's `backup-handler.ts`, which skips a title's missing
  book data and stores its statistics by folder name), and the phone.

## As built: translation

Fork code in `app/src/main/java/jp/reikai/translate/` and `jp/reikai/settings/TranslationSettings.kt`;
strings in `jp-yomitan/src/main/res/values/jp_translate_strings.xml`. The three engine files keep
Tsundoku's Apache-2.0 notice and say what was modified.

- **Engines** (`TranslationEngine`: texts in, as many out, or `TranslationFailure` with the service's
  words; never the source text as a translation): `GoogleTranslateEngine` posts a batch as lines of one
  `q` to `translate.googleapis.com/translate_a/single?client=gtx&sl=..&tl=..&dt=t` (no key, no token;
  at most 100 texts, 3,500 characters; a batch whose lines come back joined or split goes again in
  halves, down to single texts). `DeepLTranslateEngine`: repeated `text` fields, at most 50 and 10,000 characters (DeepL's
  128 KiB, nine bytes a form-encoded Japanese character), `api-free` for keys ending `:fx`, targets
  `EN-US`, `PT-BR`, `ZH-HANS`/`ZH-HANT`; DeepL's message and what 403, 429 and 456 mean.
  `OpenAiTranslateEngine`: `<address>/chat/completions` with `model` and two messages only (no
  temperature or token limit, which newer models refuse), the paragraphs as a JSON array, Tsundoku's
  novel prompt reworded for an array; an answer that is not an array of as many strings is asked again
  in halves down to one paragraph (one paragraph answered in parts is joined; an answer with no text, as
  a content filter gives, is an error). Presets (`AiPreset`) fill the address and a model: OpenAI
  (`gpt-4.1-mini`), Gemini's `/v1beta/openai/` (`gemini-2.5-flash`), DeepSeek (`deepseek-chat`),
  OpenRouter, Ollama `http://localhost:11434/v1/` (no key sent when blank), Other. Error bodies in
  OpenAI's `{"error":{"message"}}` and Gemini's list form are both read (both checked against the live
  services with a bad key). One OkHttp client from the network helper without cookies, cache or the
  Cloudflare interceptor, read timeout 2 min.
- **Translator** (`ChapterTranslator`): parses the pipeline's output, drops `rt`/`rp`, and cuts the text
  into paragraphs: runs of text and inline elements, cut at `br` and at block children (which hold
  their own); scripts, styles, `pre`, SVG and form controls are left alone; blank runs are skipped.
  Each translation replaces its run's nodes where they were; pictures inside the run stay (before the
  translation if they came before its text, else after; a `picture` with its own `img`). Elements
  holding a translation get `lang` (text straight in the body a `<span lang>`), so a Japanese chapter
  beside it in the standard WebView reader's page keeps the page's `ja`; the page starts
  `<!--reikai-translation:<lang>-->`. A batch answered with another count fails the
  whole translation; a blank answer for one paragraph leaves it as it was. Source language `ja` when
  the text looks Japanese, else `auto`; target the device's language, English on a Japanese device,
  or the one set.
- **Cache** (`TranslationCache`): `filesDir/jp_translations/<chapterId>/<lang>-<engine>-<hash>.json`,
  the paragraphs' translations as a JSON array, the hash over the paragraphs' text (SHA-256, 16 hex).
  Paragraphs rather than a finished `.html` page (the plan's wording): the Japanese reader and the
  standard reader's two modes each get the chapter in their own markup from the pipeline, and the
  same paragraphs go back into whichever one opens it, so a chapter translated in one reader opens
  translated in the other without a new request. A new text's translation by the same engine and
  language replaces the old file; the AI engine's id carries a hash of address and model, so another
  model is another translation. Settings has "Delete saved translations".
- **The seam** (one line in `NovelReaderViewModel.loadChapterHtml`): `JpTranslateHook.load(viewModel,
  chapterId, fromSource) { upstream's loader }`. A session (keyed weakly by the reader model, so
  "shown translated" lasts as long as the reading session) exists once the menu has been composed;
  without one the loader runs as upstream's. For a chapter shown translated the loader's output goes
  through the cache; a miss (the chapter's text changed) gives the original and drops the chapter from
  "shown translated". The translate tap and "Show original" hand the next load the original the
  reader already has (`reuse`), so neither fetches the chapter again (and "Show original" works
  offline); "Reload from source" never takes it.
- **Menu:** the top bar's fork seam line now adds a list (`jpReaderMenuActions`): "Translate chapter" /
  "Translating…" / "Show original" for `viewModel.chapter`, decided by what is on screen (the marker in
  its HTML), then the reader switch. A translation on screen is never sent as a source. A tap toasts
  "Translating the chapter…", translates in the model's scope (a rotation does not stop it); if the
  reader moved on meanwhile, the translation is only saved; else it waits out a load in flight
  (upstream's reload leaves one alone), reports no top line (the original's line is elsewhere in the
  translation, so the reopen lands by percent) and calls `reloadChapter(false)`. Errors are a long toast "Translation failed. <service>:
  <message>", with the progress toast cancelled first (queued behind it, the error showed late and
  briefly on the tablet); a missing key, address or model says where to set it. Nothing is sent before
  the tap.
- **Japanese reader:** a translated document (the marker) gets `lang` of its language, horizontal
  text when that is not Japanese (the reader's other settings kept; `isRtl`, tap zones and the intro
  follow), no Yomitan scanner (a tap on text opens the menu), no stored character place (it lands by
  percent) and no statistics (`PageReport.translated`, counted as incognito; the tracker now starts
  afresh after an uncounted page, so the original read after a translation counts from where it lands;
  it also no longer counts the rest of an incognito chapter's last page on leaving it).
  "Show original" after a look that did not move (the translation's reports kept the percent it first
  reported) lands on the original's stored character: by percent it landed on the page holding that
  percent, a page earlier (seen on the tablet: 1,141 of 2,180 back to 720). The standard WebView
  reader's `<html>` tag is unchanged (`ja` for a Japanese novel); the translation's own elements carry
  its language.
  The standard WebView reader's `<html>` tag carries the translation's language (`JpReaderHook.htmlTag`).
- **Settings:** Settings, Japanese, Translation: a note that the chapter's text goes to the chosen
  service, Service (Google, DeepL, AI service), Translate into (device language or one of 19), and for
  DeepL its key, for an AI service the preset, address, model and a key per preset. Keys are
  `Preference.privateKey("jp_translate_...")` (`__PRIVATE_jp_translate_deepl_key` on the device), left
  out of backups, shown only as "Key set", typed in a secure field, and removable ("Remove key":
  upstream's text row refuses an empty value).
- **Seams:** 51 in 28 files (one new line in `loadChapterHtml`; the top bar line changed in place).
- **Checked:** JVM tests (43 new: paragraphs, readings dropped, pictures kept, lang and marker, count
  mismatch, batches, the same hash across markups; each engine's request and answer against a stand-in
  client, refusals, no connection, halves, an empty AI answer; the cache, reuse, reload from source, a changed text; the
  tracker after an uncounted page); breaking the `rt` removal, the picture keeping, the count check,
  the swap, Google's error path or halves, the `picture` rule, the empty-answer check or the tracker
  change fails them. A review (fresh reviewer) found the menu could follow the session rather than the
  page, a Japanese chapter next to a translation losing `ja`, and the AI and Google fallbacks above; all
  fixed before the last tablet run: chapter 7 in the Japanese reader from the paragraphs the native
  reader had saved (no request), chapter 9 over the network (1.6 and 1.4 s) with every text paragraph
  carrying `lang="en"`, horizontal, no scanner, then "Show original" back to vertical. Upstream's 537 reader tests pass.
  Tablet (SM-X520, Kakuyomu novel, Google, 2026-09-28): chapter 5 in the Japanese reader (vertical,
  1,141 / 2,180, 52 %) translated in two requests (1.7 and 1.3 s), laid out horizontally at 51 % on the
  same passage, `lang="en"`, no scanner, no `rt`, the marker in the page (DevTools); "Show original"
  back to vertical at 1,141 / 2,180; the translation again from the cache with no request. The
  standard reader's native mode showed the same chapter translated from the Japanese reader's saved
  paragraphs (no request), chapter 7 translated over the network (1.5 and 1.6 s, readings gone), the
  WebView mode showed it from the cache after the mode change. Airplane mode: "Translation failed.
  Google Translate: Unable to resolve host ..." and the original stayed, nothing saved; a bad DeepL key:
  "DeepL: HTTP 403: the key was not accepted: Forbidden. ..." The verbose network log redacts
  `Authorization`, the only place a key goes. Not checked: DeepL or an AI service with a real key
  (their error shapes were checked live with bad keys), the phone, a chapter with pictures on the
  device (JVM only), landing after reading on in a translation.

## As built: EPUB export

Fork code in `app/src/main/java/jp/reikai/export/`; strings in
`jp-yomitan/src/main/res/values/jp_export_strings.xml`. `EpubWriter.kt` keeps Tsundoku's Apache-2.0
notice and says what was modified (Tsundoku is already credited in `LICENSES/Reader-NOTICE.md`).

- **Menu** (`JpNovelMenu`, one line at the end of `EntryToolbar`'s overflow): "Export as EPUB" when the
  navigator's top screen is a `NovelScreen` (nothing on a manga's). The tap finds the novel by
  `sourceId` and `novelUrl`; with no chapter downloaded it says so in a toast and asks for no file,
  else `CreateDocument("application/epub+zip")` offers `<title>.epub`, takes a persistable permission
  (as a backup's location does) and queues `NovelEpubJob`.
- **Job** (`NovelEpubJob`, a `CoroutineWorker`, unique work `jp_epub_export` appended, so two books are
  never written at once): foreground on upstream's common channel with "Chapter N of M" and Cancel
  (ids -9401, -9402); then "EPUB saved: <title>" with "N chapters exported, M not downloaded were
  skipped" (the second half only when something was), and a tap opens the book (`ACTION_VIEW`). An
  export with nothing in it, a failure or a cancel deletes the picked file.
- **Chapters** (`NovelEpubExport`): the ones the screen lists, in reading order: the novel's own by
  `sourceOrder`, a merged novel's whole group as its stitch orders them; user-hidden chapters left out.
  Each chapter's text from `NovelDownloadManager.getChapterText` (built on first use), through
  `NovelContentPipeline` with the reader's settings (replacement rules, hide title, block media) and
  the WebView target, but without the page's own CSS and scripts; a plain-text chapter through
  `plainTextToHtml`. A chapter with no download is counted as skipped.
- **Details**: title, author and artist, description and genres after the owner's edits
  (`withCustomInfo`). Language: the plugin's recorded language, else an app source's, else the loaded
  plugin's; a multi-language or unknown source by the first downloaded chapter's text (`ja` when it
  looks Japanese, else `und`). Cover: the owner's own cover file, else the library cover cache, else
  through Coil as a JPEG (1600 px); the plugins' "no cover" picture is no cover.
- **Book** (`EpubWriter`, streamed a chapter at a time): `mimetype` first and stored with no extra
  field, `META-INF/container.xml`, `OEBPS/content.opf` (EPUB 3; `dc:identifier` a name-based
  `urn:uuid` of source and URL, so every export of a novel has the same one; `dcterms:modified`;
  EPUB 2 `meta name="cover"` and `guide`), `nav.xhtml` (toc, hidden landmarks; "目次" for Japanese),
  `toc.ncx`, `styles/book.css`, `cover.xhtml` first in the spine, `chapterNNNN.xhtml`,
  `images/`. Every page has the book's language as `lang` and `xml:lang`. Chapters are serialised by
  Jsoup as XML (void elements closed, entities numeric, characters XML forbids dropped); scripts,
  styles, frames, players, SVG and MathML are dropped, prefixed names no namespace declares (Word's
  `o:p`, `foo:bar`) unwrapped or dropped, links kept only to the web or within the page. `data:`
  pictures become `images/<sha256-16>.<ext>`, each written once, typed by their first bytes (JPEG,
  PNG, GIF, WebP); other formats (AVIF, BMP) go through Android's decoder to JPEG or are left out;
  pictures on the web are left out. A chapter that opens with a heading of its own (Kakuyomu's part
  and episode titles) gets no second title. Japanese: `writing-mode: vertical-rl` (with the `-epub-`
  and `-webkit-` names), `page-progression-direction="rtl"`, `primary-writing-mode`, paragraphs
  unspaced and emphasis as sesame dots, as the Japanese reader sets them.
- **Seams:** 52 in 29 files (the `EntryToolbar` line).
- **Checked:** JVM tests (26, `EpubWriterTest`): the zip header (stored `mimetype` at offset 30, no
  extra field), container, package details and language, `rtl` and vertical CSS for `ja` and neither
  for `en`, cover item and spine, every document through a strict namespace-aware parser, `lang` and
  `xml:lang`, a chapter's text and readings kept and its bad names, entities, control characters,
  handlers and relative links gone, a title with markup and a line break, pictures written once,
  remote ones dropped, the converter, navigation and NCX order, a stable identifier, the skip count
  and progress, the own-heading rule. Breaking the stored `mimetype`, the XML syntax, the `rtl`
  direction, the stable identifier, the skip count, the prefix unwrap, the remote-picture removal or
  the heading rule fails them. Tablet (SM-X520, debug build, 2026-09-28, Kakuyomu novel of 33
  chapters, chapters 1 to 3 downloaded, a test cover set as the novel's own): with nothing
  downloaded the menu said so; then the picker offered `<title>.epub` in Downloads, the book was
  there 4 s after Save; with notifications allowed (granted for the test, taken back after) the
  result read "3 chapters exported, 30 not downloaded were skipped" and its tap offered the installed
  EPUB readers; ReadEra showed the cover, then chapter 1
  vertically with the source's own heading once. The pulled file: `mimetype` stored first with no
  extra field, every document parsed by `xmllint`, `dc:language` `ja`, spine `rtl`, cover first, the
  same `dc:identifier` on two exports. `epubcheck` is not installed and was not run. Not checked on
  the device: a chapter with pictures (none in these chapters; JVM only), a merged novel, Cancel, a
  non-Japanese novel, the phone.
