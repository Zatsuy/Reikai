# Automatic Yomitan updates (roadmap 3.6)

Reikai JP ships Yomitan's official release unmodified (`jp-yomitan/src/main/assets/yomitan/`,
recorded in `jp-yomitan/yomitan-release.json`). Nobody tends the updates (decision D-015): they
apply themselves when the checks below pass, and wait for an agent when one fails.

## What runs every week

`.github/workflows/fork-yomitan-update.yml`, Wednesdays 05:30 UTC (and by hand from the Actions
tab), one job on `ubuntu-latest`:

1. `scripts/fork/yomitan_bump.py check`: GitHub's release list for `yomidevs/yomitan`; it takes the
   newest promoted release (never a draft or pre-release) that is newer than the vendored version
   and has been out 7 days, so weekly releases still arrive, each a week late. Most weeks it stops
   here (well under a minute; a year of weekly checks is about 52 billed minutes).
2. Only when there is a release to take: `yomitan_bump.py update <tag> --no-smoke`:
   - **vendor**: downloads `yomitan-firefox.zip`, checks its SHA-256 against GitHub's asset digest,
     deletes the vendored tree, unzips the release in its place and rewrites
     `yomitan-release.json`; **verify** checks every file against that record;
   - **tripwire**: the static check below;

   then `npm ci` here (just the pinned `playwright-core`, no install scripts, no browser download)
   and **smoke**: `smoke.mjs` in the runner's Google Chrome. The Actions token reaches only the
   keepalive, the release check and the push, never npm or the browser.
3. All passed: commits `chore(yomitan): update to <tag>` (the vendored tree, staged with `git add
   -f` so no `.gitignore` rule drops a file, and `yomitan-release.json`), checks out that commit
   afresh and runs `verify --root` on it, then pushes it to `main`. The daily App release workflow
   ships it the next morning. If `main` moved during the run, nothing is pushed and the next week's
   run tries again. An update run takes a few minutes; Yomitan releases every few weeks, so the
   whole workflow costs roughly 80 to 100 CI minutes a year.

A failure pushes nothing. The next agent session's start lines say "GitHub automation failed:
Yomitan update"; the run's summary shows the last lines of the update log.

## The two checks

**Static tripwire** (`yomitan_bump.py tripwire`, Python standard library only). It reads, from
Yomitan's own scripts with comments stripped (so JSDoc types and copyright years never count), the
lists the stand-in, the hub and the app rely on, and compares them with the reviewed baseline
`jp-yomitan/yomitan-surface.json`: `chrome.*` API chains, AnkiConnect actions, audio source types,
the backend's message actions, the API client's actions, manifest permissions, background page
and content script, the IndexedDB name, version and schema, the bundled npm libraries and their
licences, every line with a sensitive pattern (workers, `serviceWorker`, `SharedWorker`, the
database worker handshake, zip.js's worker set-up, the backend's ready signal, every `fetch`,
XHR and IndexedDB call), fingerprints of the functions the stand-in imitates or depends on
(`Application.main`, `API._pmInvoke`, `RequestBuilder.fetchAnonymous`, the database's `prepare`
and `drawMedia`, the small worker entry files, the display history and theme the lookup sheet drives,
the settings exports the stand-in saves), the entry files the app loads by name, and the page
markup the app's hosts find by id (the popup's notices and close button, the settings sections and
the recommended-dictionaries list). Any
item added or removed fails the update with a `-`/`+` list per surface. Blind spot: a behaviour
change behind unchanged names and code outside those functions.

**Smoke test** (`yomitan_bump.py smoke`, `smoke.mjs` with `hub.mjs`). Headless Chrome as a stand-in
for Android WebView: the release is served at `https://yomitan.reikai.invalid` through request
interception; each page gets what WebView gives it (no `SharedWorker`, the `reikaiHub` object, then
the stand-in) and talks to `hub.mjs`, a JavaScript copy of `YomitanHub.kt` running in Node (keep
the two in step). A worker's own worker loads in neither: WebView never serves it, and Chrome sends
it past the test's routing to the unresolvable `.invalid` host, so if zip.js ever starts its
worker again (`lib/z-worker.js`) the import hangs and the run fails, as it would on a device. The
run opens `background.html` and waits for the backend's
`applicationBackendReady`, imports `test-dictionary.zip` through the settings page's "Import from
URL", looks up 打ち込んだ through the app's `findTerms` path (expects 打ち込む), opens the search
page for 画像 and waits for its dictionary picture to be drawn by the page's own database worker,
then switches Anki on and asks Yomitan for AnkiConnect's version: Yomitan's own POST goes through
the stand-in and the hub to a canned AnkiConnect, and the answer comes back as a binary
(ArrayBuffer) message, the path a device uses for Anki and audio. It fails on any tripwire or stand-in "called" entry, stand-in error or uncaught page error (one
known race in Yomitan's own settings preview excepted, see `knownRace` in `smoke.mjs`). It passes
in about 4 seconds here, and 12 of 12 runs passed with every page slowed 6 times
(`REIKAI_SMOKE_SLOWDOWN=6`), so a slow runner is no reason for a failure.

`test-dictionary.zip` is Yomitan's own test dictionary `test/data/dictionaries/valid-dictionary1`
(Copyright (C) 2023-2026 Yomitan Authors, GPL-3.0-or-later, the licence of Reikai JP), zipped
unchanged. It is a test fixture only, never part of the app. To rebuild it from `../refs/yomitan`,
zip that directory's files at the archive root.

## When the update fails: what an agent does

Reproduce locally first; never spend CI runs on it (D-021).

```sh
npm ci --prefix scripts/fork/yomitan-smoke
# Chrome: the installed Google Chrome, or $REIKAI_CHROME, or Playwright's Chromium downloaded once:
PLAYWRIGHT_BROWSERS_PATH=build/ms-playwright \
  node scripts/fork/yomitan-smoke/node_modules/playwright-core/cli.js install chromium-headless-shell
PLAYWRIGHT_BROWSERS_PATH=build/ms-playwright scripts/fork/yomitan_bump.py update <tag>
```

`update` leaves the new release in the working tree on failure, so `git diff --stat` and
`git diff -- jp-yomitan/src/main/assets/yomitan/<file>` show what Yomitan changed.

- **Tripwire.** Read each listed change in Yomitan's code and decide whether the stand-in
  (`jp-yomitan/src/main/assets/jp-reikai/`), the hub (`YomitanHub.kt`, and `hub.mjs` with it, for
  example `CONTENT_ACTIONS` for a new content-script action), the engine or the licence notice
  (`LICENSES/Yomitan-NOTICE.md` for a new library) must change. A new IndexedDB version needs
  extra care: it cannot be rolled back on the owner's devices. Make those changes, then regenerate
  the baseline from the reviewed release and say in the commit what was reviewed:
  `scripts/fork/yomitan_bump.py tripwire --write`. Only an agent does this, never the workflow.
- **Smoke test.** Treat it as a bug (`/debug`): the log names the step that failed. Suspect timing?
  Rerun with `REIKAI_SMOKE_SLOWDOWN=6` a few times rather than on CI. If the stand-in must change,
  prove it on a device too (`scripts/fork/yomitan_check.py`).
- **Browser driver.** If Chrome and the pinned `playwright-core` stop talking to each other, raise
  the version in `package.json` and run `npm install --prefix scripts/fork/yomitan-smoke` to
  refresh `package-lock.json`.

Then commit the update (`chore(yomitan): update to <tag>` plus the fixes; stage the vendored tree
with `git add -f jp-yomitan/src/main/assets/yomitan`, since a `.gitignore` rule would silently drop
a matching file) and push `main` after `/verify`. The next weekly run finds the release vendored and clears the session-start line.

## Updating by hand

`scripts/fork/yomitan_bump.py update <tag>` does the same as the workflow for any promoted release
(no 7-day wait when a tag is given); `--no-smoke` skips Chrome. A dry run of the whole path with
the current release: `scripts/fork/yomitan_bump.py update --pinned <an older version>` (check,
vendor, verify, tripwire and smoke; the tree ends unchanged when that release is already vendored).
To pin a release's zip hash in the script, add it to `PINNED` in `yomitan_bump.py`.
