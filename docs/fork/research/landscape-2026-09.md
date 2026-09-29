# Research: the landscape, September 2026

Findings from the 2026-09-26/27 research pass that shaped [architecture.md](../architecture.md).
Reference clones live in `../refs/` (sibling of the repo): mihon, lnreader, lnreader-plugins,
tsundoku, chimahon, yomihon, hoshidicts, yomitan, ttu-ebook-reader, Hoshi-Reader. Citations are
`repo:path:line` at the SHAs of that date. Re-verify before relying on a line number.

## Upstream Reikai

- Real development happens on `feat/<version>`; `main` is only the last release. On 2026-09-26
  `feat/0.4.0` was 1,848 commits ahead of `main`, synced to Mihon `f52d890e7` (2026-09-23), already
  on AndroidX ViewModel and Metro DI.
- Novels open in Mihon's `ReaderActivity`; `NovelReaderProvider.createViewport` (`:273`) picks the
  native text viewport (default, `ln_reader_rendering_mode` = `NATIVE`) or the WebView viewport,
  both behind `ReaderViewport`. Neither has vertical text or paging; upstream's roadmap has nothing
  Japanese.
- Upstream churn in 30 days: `CHANGELOG.md` 344 edits, `ROADMAP.md` 106, `.claude/rules/` 45,
  `.github/workflows/` 24, `CLAUDE.md` 17. Fork files stay out of those paths.

## LN plugin hosts (Reikai vs Tsundoku vs LNReader)

Reikai's QuickJS host matches LNReader on the contract that matters: the path a plugin returned is
passed back unchanged, WHATWG `URL`, real cheerio (parse5), browser-like headers plus the device
User-Agent, `fetchText` returns `''` on failure, no throttling. Tsundoku departs in three places,
which explain the owner's reports:

1. **Kakuyomu `parseNovel` "cannot read property 'author' of undefined"** (reproduced): Tsundoku
   stores `/works/<id>` then strips the slash (`tsundoku:.../jsplugin/source/JsSource.kt:845-853,
   925-930`); the plugin matches `novelPath.replace('/works/', '')`
   (`lnreader-plugins:plugins/japanese/kakuyomu.ts:100`), finds nothing, and `.author` throws.
2. **Missing first search result** (mechanism reproduced): Tsundoku's `URL` polyfill returns the
   address frozen at construction, dropping `searchParams` (`.../jsplugin/library/JSLibraryProvider.kt:2279-2295`),
   so Kakuyomu search fetches `/search` with no query and gets the weekly ranking.
3. **Slow Syosetu** (strongly suggested): a global per-host throttle, 3 s plus up to 1 s jitter, one
   request at a time, on by default; its on-screen exemption compares `yomou.syosetu.com` while the
   pages come from `ncode.syosetu.com`.

Reikai's own gaps for Japanese sites: `fetchText` ignores its encoding argument and `TextDecoder`
is UTF-8 only (Shift-JIS/EUC-JP sites would garble); `Intl` is a locale-blind stub. *Corrected
2026-09-27 (roadmap 1.4):* the host already decoded by the Content-Type charset (OkHttp), so only
pages that name their charset in `<meta>` garbled (Aozora Bunko); no LNReader plugin passes an
encoding today. The "one engine-wide lock" was gone before the fork began: upstream `93d8a0425`
gives each plugin its own engine and lock, and global search runs 5 sources at a time
(`ENTRY_ROW_CONCURRENCY`). Only calls to the same plugin still queue.

**Kakuyomu Popular/Latest is a plugin bug.** The rankings page moved to Next.js; the selector
`.widget-media-genresWorkList-right > .widget-work` (`kakuyomu.ts:68`) matches nothing, so every host
gets HTTP 200 and an empty list. Draft upstream issue, not filed:

> **kakuyomu: Popular (and Latest) return no novels; ranking page migrated to Next.js.**
> `popularNovels` fetches `https://kakuyomu.jp/rankings/{genre}/{period}` (HTTP 200) but parses it
> with `.widget-media-genresWorkList-right > .widget-work` / `a.widget-workCard-titleLabel`
> (kakuyomu.ts L68-69). Those classes no longer exist. The ranked works are in
> `script#__NEXT_DATA__` → `props.pageProps.__APOLLO_STATE__.ROOT_QUERY["rankedWorks({...})"].nodes`,
> an ordered list of `Work:<id>` references into the same Apollo state `searchNovels` already reads.
> Mapping each node to `{ name: work.title, path: '/works/' + work.id, cover: work.adminCoverImageUrl ?? defaultCover }`
> fixes it; `?page=N` still paginates and the genre/period values still resolve. The plugin also
> ignores `showLatestNovels`, so Latest shows the same ranking. Search is unaffected.

*Superseded 2026-09-27 (roadmap 1.5):* listing pages carry no cover URL, and rankings redirect to add
`work_variation=long`; the corrected text and a tested fix are in [upstream-prs](../upstream-prs/README.md).

## Extension language filter (owner's report)

On 0.4.0 extensions are already grouped under per-language headers, but **no language filter
applies to novels**: `GetExtensionsByType.kt:29-31` passes no languages for novels, LN plugins skip
the check (`ExtensionsProvider.kt:185-209`), and the Filter item is hidden on the Novels chip
(`ReikaiExtensionsTab.kt:117-122`). Plugin language names map to ISO codes already
(`NovelSourceLanguage.kt:10-14, 39-56`). Minimal fix: filter available rows by Mihon's
`enabledLanguages` in `ExtensionsEngine.kt` (one mechanism for manga and novels), add novel and
plugin languages to `GetExtensionLanguages.kt`, show the Filter item for novels, auto-enable a
language when a plugin in it is installed (Tsundoku's `JsPluginManager.kt:270-276`). Installed
novel *sources* use a separate deny list (`ReikaiSourcePreferences.disabledNovelLanguages`), see
decisions O-001. LNReader: `src/hooks/persisted/usePlugins.ts`, `BrowseSettings.tsx:84-89`.

## Novel readers and Japanese

- No reader among Tsundoku, LNReader and Reikai does vertical text, character counts or lookup.
  Tsundoku's native renderer flattens `<ruby>` into inline text (`Html.fromHtml` with no tag
  handler, `NovelTextRenderer.kt:66-71`). A WebView does vertical text, ruby and pagination natively.
- Tsundoku is not a syncable upstream: its reader is about 2,600 unfenced diff lines inside
  Mihon's `ReaderActivity`/`ReaderViewModel`. Its feature list is the parity target (about 60
  options: typography, themes, tap zones, paged mode (WebView only), infinite scroll, TTS with
  highlight, regex cleanup, CSS/JS snippets, status bar, EPUB import/export, translation, quotes).
- Tap-to-lookup in a WebView (Chimahon's `reader.js:487-795`, from Hoshi Reader):
  `caretPositionFromPoint` → hit-test the character's rects, skip `rt`/`rp` → walk to the sentence
  delimiters with a `TreeWalker` → post `(word, sentence, rect)` → highlight the match with the CSS
  Custom Highlight API. Yomitan's own `TextSourceGenerator` does the same with its sentence rules.
- ttu (BSD-3, maintenance only): vertical/horizontal, paginated/continuous, furigana modes Hide /
  Partial / Toggle / Full, character count = kana + CJK + alphanumerics with `rt` excluded
  (`get-character-count.ts:9-19`), reading statistics and goals, Google Drive sync.

## Yomitan

- GPL-3.0-or-later. `ext/js` is about 85k lines. Activity slowed: 102 commits in 12 months, 13 in
  the last 3.
- **Runs headless:** dictionary import, lookup (deinflection) and Anki field rendering ran in plain
  Node with a fake IndexedDB; upstream's tests (1,472) pass. Its golden files
  (`test/data/translator-test-results.json`, `anki-note-builder-test-results.json`) are a
  conformance suite for any re-host.
- **Stable contracts:** dictionary zip format and schemas (4 additive changes in 24 months), the
  Japanese deinflection tables (data-like, about one change a quarter), the `DictionaryEntry`
  shape, Anki marker names (only ever added), the structured-content generator (no changes in 12
  months), the settings export format with `OptionsUtil` migrations, the Yomitan API action set.
- **Fast-moving:** `background/backend.js`, `comm/`, settings pages, `display/`: where the
  `chrome.*` coupling lives.
- **Embedding facts:** Firefox build runs the backend as a page (`background.html`); with no
  `chrome.offscreen` it builds DB and translator in-process (`backend.js:67-73`); the display takes
  `?query=&full=&offset=` (`display.js:836`); Handlebars is interpreted, no eval needed; the
  service-worker trap in `api.js:488-505` needs a direct message port; absolute root paths
  (`/js/...`, `/lib/resvg.wasm`) mean the files must be served at an origin root; audio and
  AnkiConnect requests must go through native code (CORS). The Yomitan API (`comm/yomitan-api.js:160-273`:
  `termEntries`, `kanjiEntries`, `ankiFields`, `tokenize`, `ankiCardFormats`) is a ready contract.
- Nobody embeds Yomitan's JS in an app today; every Android project reimplemented it natively.
- Mobile complaints to design against: popup selection scrolls the page (yomidevs/yomitan#2452),
  search box out of thumb reach (#2273), hide-popup-for-a-while (#2222), trim the lookup span
  (#2464), interrupted import leaves a white screen (#2169).

## Other immersion apps

| Project | Licence | Engine | Notes |
|---|---|---|---|
| Chimahon | GPL-3.0 | hoshidicts (GPL) via JNI, own `renderer.js` | Closest product: 8 lookup surfaces, Hoshi-derived vertical reader, Lapis mining; one maintainer, few patch markers; its "local OCR" loads Google's closed Lens binaries (avoid) |
| Yomihon | Apache-2.0 | hoshidicts MIT fork | Native Compose glossary (no images), fixed marker set, manga-ocr TFLite bundled (APK 188-256 MB) |
| hoshidicts | GPL (`main`) / MIT (`main-mit`, frozen since 2026-04) | C++, mmap + hash + Bloom | `main`'s deinflector is a Yomitan port; fastest option; no JNI upstream |
| Hoshi Reader | GPL-3.0 | hoshidicts | iOS; "best app currently for reading on a mobile device" (TheMoeWay); Android port exists |
| jidoujisho | GPL-3.0 | own | Dormant since 2025-10 |

## Anki, audio, note types

- AnkiDroid: ContentProvider `com.ichi2.anki.flashcards`, dangerous permission
  `com.ichi2.anki.permission.READ_WRITE_DATABASE` (runtime grant; install-order `SecurityException`
  to handle), `<queries>` for package visibility, duplicate check by first-field checksum on
  `notes_v2`, media via `FileProvider` + `grantUriPermission` (stored names get a random suffix),
  card-state columns (`interval`, `due`, `queue`, FSRS) from AnkiDroid 2.24.0 (2026-05). The API
  library is LGPL-3.0 on JitPack only; raw ContentProvider access avoids the dependency.
- Audio: Yomitan sources jpod101, language-pod-101, jisho, lingua-libre, wiktionary, tts, custom,
  custom-json (`audio-downloader.js:49-59`). Local audio: the community `android.db` (tables
  `entries`, `android`) used by AnkiConnect Android and Chimahon. Never ship audio.
- Note types: Lapis (GPL-3.0, TheMoeWay default), Kiku (MIT) and Senren use only stock markers
  (`{expression}`, `{furigana-plain}`, `{popup-selection-text}`, `{single-glossary-<dict>}`,
  `{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}`, `{pitch-accent-positions}`,
  `{frequency-harmonic-rank}` with its `9999999` no-data sentinel, `{document-title}`,
  `{sentence-furigana-plain}`). JPMN needs real Handlebars plus Yomitan helpers. Running Yomitan's
  renderer covers all of them.

## Manga OCR

Superseded by [manga-ocr-2026-09.md](manga-ocr-2026-09.md) (2026-09-28), which corrects this section.


- Clean-licence options: manga-ocr (Apache-2.0; mobile TFLite ports 20-140 MB), PP-OCRv6_manga
  (Apache-2.0, about 11 MB detection plus recognition), `.mokuro` import (format free to parse; the
  tool is GPL). ML Kit is proprietary (blocks F-Droid, needs Play services). Google Lens (both forks'
  default) is an unofficial endpoint with a scraped key. comic-text-detector is GPL; the YOLO26n
  panel detector became AGPL in 2026-09.
- Overlay attach point: `ReaderPageImageView`, boxes stored in image coordinates and mapped with
  `SubsamplingScaleImageView.sourceToViewCoord`; WebGPU viewer has no SSIV (gate it off).

## Licensing

- Apache-2.0 → GPL-3.0 is allowed one way (FSF, ASF). The fork as a whole is GPL-3.0-or-later;
  upstream's Apache notices stay (`LICENSES/Apache-2.0.txt`).
- Firebase Analytics is closed source and incompatible with shipping GPL code; upstream only links
  it with `-Pdist=ci|github` or `-Pinclude-telemetry`. The fork builds `local`/`foss` only.
- Dictionaries: JMdict/KANJIDIC are CC BY-SA 4.0 with an update obligation; frequency lists
  (BCCWJ research-only, JPDB unstated). Users import their own.

## Harness research (2026-09-27)

- Claude Code 2.1.283: AGENTS.md is read natively only when no CLAUDE.md exists, so CLAUDE.md
  imports it; rule files load every session unless scoped with `paths:` (`alwaysApply` is ignored);
  hook `timeout` is in seconds; a hook that cannot start is a non-blocking error (upstream's hook
  scripts were never executable on Linux, so none of them ran); `attribution` controls commit
  trailers; transcripts are `~/.claude/projects/<dir>/<session>.jsonl` (+ `subagents/`), internal
  format, re-check after upgrades.
- Evidence for a measured harness: METR (developers felt faster while being slower); ETH Zurich
  (LLM-written context files lowered success and raised cost); ACE (regenerating context collapses
  it, small curated edits help); Anthropic (separate the evaluator from the generator; every harness
  piece encodes an assumption, remove one at a time to test it).
- Borrowed skill sources: mattpocock/skills (MIT: grilling, diagnosing-bugs, tdd, code-review,
  wizard, to-questionnaire, retro, writing-for-agents), obra/superpowers (MIT:
  verification-before-completion, model selection and rulings from subagent-driven-development),
  android/skills (Apache-2.0: android-cli, r8-analyzer), anthropics/skills (Apache-2.0:
  skill-creator for skill evals).
