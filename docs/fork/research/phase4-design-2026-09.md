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
margins: {top,right,bottom,left} (px), insets: {top,bottom} (px, the system bars),
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

`pos` = `{ charOffset, chars, fraction, page, pages, fits, endSeen }`: characters before the first
visible character (ttu's rule), the chapter's total, their ratio, the 1-based page and page count
(in scroll mode screens), whether the whole chapter fits on one page, whether its end is on
screen.

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
lookup script; `jp-reader.js` calls it for a tap on a character when `tapMode` is `lookup`
and posts `tap` only when it returns false or is unset. `JpReader.charRangeAt(x, y)` answers
whether a point is on a character.

**Character counting** is ttu's exactly: text of text nodes outside `rt` and outside
`[hidden]`/`[aria-hidden]` subtrees, with
`/[^0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu` removed,
counted in code points; an `img` whose class contains `gaiji` counts 1.

## Budgets

Chapter open not slower than upstream's WebView mode on the tablet; a page turn inside one frame;
re-layout after a settings change under 300 ms for a 20 000-character chapter on the tablet; tap
to popup as Phase 3 (p95 under 150-200 ms).
