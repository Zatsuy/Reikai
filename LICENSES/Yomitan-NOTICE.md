# Yomitan in Reikai JP

Reikai JP ships Yomitan's official release unmodified, as its lookup engine
(`jp-yomitan/src/main/assets/yomitan/`).

- **Source:** Yomitan **26.9.8.0**, tag `26.9.8.0` of <https://github.com/yomidevs/yomitan>, release
  asset `yomitan-firefox.zip` (SHA-256
  `8c23aa2d61ebeefdfec8606394411e19bc24e5141fea61888f1d2690f5375217`). The exact version, URL and
  per-file hashes of what is vendored are in `jp-yomitan/yomitan-release.json`, written by
  `scripts/fork/yomitan_bump.py`; when that file names a newer version, it is the one shipped.
- **Yomitan** is Copyright (C) 2023-2026 Yomitan Authors and Copyright (C) 2016-2022 Yomichan
  Authors, licensed under the GNU General Public License version 3 or (at your option) any later
  version: the same licence as Reikai JP, text in `LICENSE` at the repository root. Its source code
  is at the tag above.
- **EDRDG:** Yomitan uses the EDICT and KANJIDIC dictionary files. These files are the property of
  the Electronic Dictionary Research and Development Group, and are used in conformance with the
  Group's licence (<https://www.edrdg.org/edrdg/licence.html>). Reikai JP itself bundles no
  dictionary.
- **Noto Sans JP** (`fonts/NotoSansJP-Regular.ttf`): (c) 2014-2021 Adobe
  (<http://www.adobe.com/>), with Reserved Font Name 'Source'; SIL Open Font License 1.1,
  `OFL-1.1.txt`.
- **KanjiStrokeOrders font** (`data/fonts/kanji-stroke-orders.ttf`), whose own notice reads: "This
  font may be freely distributed under the terms of the BSD-style license that accompanies this
  font in the file copyright.txt. The kanji stroke order diagrams remain under the copyright of
  Ulrich Apel and the Wadoku and AAAA projects." (`BSD-3-Clause.txt` has the BSD terms.)
- **`fallback-bloop.mp3`** is by UNIVERSFIELD (<https://pixabay.com/sound-effects/error-8-206492/>)
  under the Pixabay Content License (<https://pixabay.com/service/license-summary/>).
- **JavaScript libraries** bundled by Yomitan (its `legal-npm.html`, shipped with it, lists them
  too); each is copyright its authors, see the link:

| Library | Version | Licence | Text | Link |
|---|---|---|---|---|
| @resvg/resvg-wasm | 2.6.2 | MPL-2.0 | `MPL-2.0.txt` | <https://github.com/yisibl/resvg-js> |
| @zip.js/zip.js | 2.7.54 | BSD-3-Clause | `BSD-3-Clause.txt` | <https://github.com/gildas-lormeau/zip.js> |
| dexie | 4.0.11 | Apache-2.0 | `Apache-2.0.txt` | <https://github.com/dexie/Dexie.js> |
| dexie-export-import | 4.1.4 | Apache-2.0 | `Apache-2.0.txt` | <https://github.com/dexie/Dexie.js> |
| hangul-js | 0.2.6 | MIT | `MIT.txt` | <https://github.com/e-/Hangul.js> |
| kanji-processor | 1.0.2 | not stated | | <https://registry.npmjs.org/kanji-processor/-/kanji-processor-1.0.2.tgz> |
| parse5 | 7.2.1 | MIT | `MIT.txt` | <https://github.com/inikulin/parse5> |
| yomitan-handlebars | 1.0.0 | MIT | `MIT.txt` | none given in Yomitan's licence report |
| linkedom | 0.18.10 | ISC | `ISC.txt` | <https://github.com/WebReflection/linkedom> |

The MIT, ISC and BSD-3-Clause texts here are the SPDX templates; each library's copyright line is
in its own repository.
