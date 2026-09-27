# Fixes offered to other projects

General fixes the fork made that belong upstream. Each has its patch or branch and the text for
the pull request. Opening one is optional and public, under the owner's GitHub account.

| Fix | For | Where it is | State |
|---|---|---|---|
| Plugin pages in Shift_JIS and other non-UTF-8 charsets | `unseensnick/Reikai` (`feat/0.4.0`) | branch `pr/plugin-charsets` on `Zatsuy/Reikai`; text below | ready, not opened |
| Three tests that fail at random | `unseensnick/Reikai` (`feat/0.4.0`) | branch `pr/recents-test-flakes` on `Zatsuy/Reikai`; text below | ready, not opened |
| Kakuyomu Popular and Latest | `LNReader/lnreader-plugins` (`master`) | [lnreader-plugins-kakuyomu.patch](lnreader-plugins-kakuyomu.patch); text below | ready, not opened |

## Reikai: decode plugin pages that are not UTF-8

Title: `fix(novel): decode shift_jis and other non-utf-8 plugin pages`

> ## Summary
>
> Light-novel plugins received every page as UTF-8 unless the server named another charset in its
> Content-Type header. Pages that name their charset only in a `<meta>` tag reached plugins as
> mojibake: Aozora Bunko sends `text/html` with no charset and `<meta ... charset=Shift_JIS>`
> (`https://www.aozora.gr.jp/cards/000148/files/773_14560.html`). `fetchText`'s third argument
> (lnreader's `encoding`) was dropped, and `TextDecoder` ignored its label.
>
> The bridge now picks the charset the way a browser does: byte order mark, the plugin's label,
> the Content-Type charset, a `<meta charset>` in the first 1024 bytes of an HTML page, then UTF-8.
> Labels follow the WHATWG Encoding Standard (every Shift_JIS label decodes as windows-31j, which
> has the circled digits web novels use; GB labels as GB18030). `TextDecoder('shift_jis')` decodes
> through a new sync host function, and `arrayBuffer()` returns the raw bytes of a non-UTF-8 page
> instead of their UTF-8 re-encoding. UTF-8 pages take the same path as before and carry no extra
> copy.
>
> Tests: `LnBodyDecoderTest` and `LnHostBridgeCharsetTest` (JVM), `LnCharsetDeviceTest`
> (on-device, the real QuickJS host; the Aozora cases need the network).
>
> ## Checklist
>
> - [x] Tested on a device or emulator (Galaxy Tab S10 FE, Android 16: `LnCharsetDeviceTest` 5/5)
> - [x] Updated `CHANGELOG.md` under `[Unreleased]`
> - [x] Edits to Mihon's own files are fenced (none: only `reikai.novel.host` files)
> - [x] Commits follow `type(scope): summary`

## Reikai: three test races

Title: `fix(test): end three test races that fail ci at random`

> ## Summary
>
> Three unit tests fail at random on CI, and all are test races rather than app bugs.
>
> - `RecentsEngineTest` "a read row leaves the downloaded filter once its download is deleted"
>   deletes the download right after the first render. The fake's download signal is a
>   `MutableSharedFlow` without replay, and `rendered` subscribes to it on `Dispatchers.Default`
>   after `onStart`, so when the delete wins the race the signal is dropped and the test waits out
>   runTest's 60 s timeout (`UncompletedCoroutinesError`). It now calls
>   `provider.awaitDownloadWatcher()` first, as its neighbours do.
> - `RecentsFeedSurfaceTest` never ends the view models it builds. The History models run
>   `flowOn(Dispatchers.IO)` inside `flatMapLatest`; when that work finishes after
>   `Dispatchers.resetMain()`, its parent resumes on Main and throws, and kotlinx-coroutines-test
>   reports it on the next test as `UncaughtExceptionsBeforeTest`. The test now cancels and joins
>   the model's scope while Main is still the test dispatcher (cancelling without the join is not
>   enough).
> - `RelatedMangasBrowseViewModelTest` "an added title stays marked in the library when the pool
>   updates" updates the pool as soon as the add finishes. The grid combines the pool and the
>   library on separate collectors, so on a slow runner the pool's emission can arrive first and
>   the first two-item state shows the added title outside the library. The test now waits for the
>   mark before updating the pool.
>
> The failures rarely reproduce by rerunning alone. With the bad timing forced by a small delay
> agent, the old tests failed 20 of 20, 10 of 10 and 20 of 20 runs and the fixed ones 0; removing
> the join makes the feed test fail again 5 of 5; 30 unforced runs of each class pass.
>
> ## Checklist
>
> - [x] Commits follow `type(scope): summary`
> - Test-only change, so no device test, changelog entry or screenshots.

To open either Reikai one: on GitHub, `Zatsuy/Reikai` → its branch → Contribute → Open pull request, base `unseensnick/Reikai` `feat/0.4.0` (retarget to upstream's newest `feat/<version>`
if it moved), paste the text above.

## lnreader-plugins: Kakuyomu Popular and Latest

Title: `fix(japanese/kakuyomu): parse rankings from next data and add latest`

> Popular returns no novels: `/rankings/{genre}/{period}` is now a Next.js page (it also answers
> 307 to add `?work_variation=long`), and the selector
> `.widget-media-genresWorkList-right > .widget-work` no longer matches anything, so every app gets
> HTTP 200 and an empty list (`npm run check:plugin -- plugins/japanese/kakuyomu.ts`:
> `popularNovels FAIL: Returned no novels`).
>
> The ranked works are in `script#__NEXT_DATA__` → `props.pageProps.__APOLLO_STATE__.ROOT_QUERY`,
> key `rankedWorks({...})`, whose `nodes` are ordered `Work:<id>` references into the same Apollo
> state `searchNovels` already reads. This change maps them to `{ name: title, path: '/works/' +
> id, cover: adminCoverImageUrl ?? defaultCover }` (listing pages carry no cover URL) and requests
> `work_variation=long` directly. `?page=N` still paginates and every genre and period value still
> resolves.
>
> It also adds Latest (`showLatestNovels`, previously ignored):
> `/search?order=last_episode_published_at`, with the genre filter as `genre_name`, read from its
> `searchWorks({...})` key. Version 1.0.0 → 1.1.0.
>
> Checked: `npm run check:plugin` passes all four checks (100 novels, search, 761 chapters, a
> chapter of 8196 characters); eslint and prettier pass; ranking pages 1-3 across genres and
> periods return 100 distinct works each; Latest returns 20 per page without overlap. Tested in an
> LNReader-plugin host on Android (Reikai JP): Popular, filters, paging, Latest, search and chapters.
>
> This fix was prepared with an AI coding agent and checked as described above.

The last sentence is required by that repository's contribution rules (AI authorship must be
disclosed); it is not a commit trailer.

To open it: fork `LNReader/lnreader-plugins` on GitHub, create branch `fix/kakuyomu-rankings`,
apply the patch (`git apply lnreader-plugins-kakuyomu.patch`), commit with the title above, push,
open the pull request against `master` with the text above.
