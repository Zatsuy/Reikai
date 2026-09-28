# Reikai JP changelog

Changes you will notice in the app, newest first. Upstream Reikai's own changes are in its
[CHANGELOG.md](../../CHANGELOG.md); this file lists only what the fork adds or changes.

## Unreleased

### Japanese: look up words with Yomitan
- New **Settings → Japanese**. Word lookup runs the real Yomitan (the same code as the browser
  extension) inside the app. It is on by default; switch it off there and it never starts, so it
  costs no memory (switched off while the app runs, the memory comes back at the next start).
- Dictionaries are not included: **Get recommended dictionaries** opens Yomitan's own list, where
  one tap downloads Jitendex (about 3 minutes) or others. The count shows on the Japanese screen.
- In the novel reader, a long-press on Japanese text now selects the whole word, as Firefox does
  (before, it often took one kanji), and **Look up** is the first button in the selection bar.
  The results open in a sheet: drag its handle to resize, swipe down or tap outside to close. It
  opens at the top of the screen when it would otherwise cover the word. Words tapped inside the
  results open in the same sheet, with back and forward.
- **Look up in Reikai JP** appears in the text-selection menu of every app (browser, notes, ...).
  **Add the dictionary to the home screen** (Settings → Japanese) puts a search screen on your home
  screen; your long-press menu keeps Browse.
- **AnkiDroid**: tap Allow once, then **Set up cards for Lapis** and pick a deck. Cards get the
  word, reading, furigana, definition, the sentence with the word in bold, the chapter as the
  source, pitch, frequency and audio; a word already in your deck shows as a duplicate.
- **Audio**: word recordings from the internet, from an optional local audio file (`android.db`,
  copied into the app, so it needs its size in free space once), and your device's Japanese voice
  as a last resort.
- **Import your desktop Yomitan settings** and **All Yomitan settings** open Yomitan's own settings
  page; exports save wherever you choose. Yomitan's settings travel in the app's backups;
  dictionaries do not (download them again after a reinstall).
- Japanese novels now draw Japanese character shapes (not Chinese ones) on a device set to another
  language.
- Settings → Advanced → Clear WebView data keeps your dictionaries.
- Yomitan updates itself: a weekly check takes a new Yomitan release once it is a week old and has
  passed our checks, and it arrives with the next app update. The app is about 15 MB bigger.

### Browse
- Browse → Extensions → Filter now works for novels too: novel plugins and novel extension apps on
  offer only show in the languages you switch on there. It is one setting for manga and novels,
  so switching 日本語 on shows Japanese manga extensions and sources as well. Extensions you already
  installed always stay listed, whatever the filter says. After this update the languages of the
  plugins you already installed are switched on once, so none of their repos' plugins vanish.
  Browse → Sources → Filter → Novels still has its own switches for installed novels.

### Novels
- Novel plugins now read sites whose pages are not in UTF-8 (Shift_JIS, EUC-JP, GBK), such as
  Aozora Bunko, as proper Japanese text instead of garbled characters.

### Other
- Speed and memory are now measured on your tablet and phone ([baseline](perf/README.md)). For
  that, both carry a second "Reikai JP" icon (a test copy with a made-up library in a
  `ReikaiJPBench` folder): ignore it, it never touches your own library. The app itself only gains
  two timing lines in the system log, with no visible effect.
- The app is called Reikai JP and installs beside upstream Reikai. New versions are published on
  this fork's GitHub Releases and the app offers them itself ([install guide](install.md)).
- About shows the version as "Nightly r" plus a number (higher is newer) and its GitHub link
  opens this fork. Like
  upstream's nightly builds, the app has its own icon colour and a few developer options.
- The fork is based on upstream Reikai's newest development branch (0.4.0 in progress).
- The fork runs none of upstream's publishing pipelines; its own checks run on GitHub instead.
- Licence: GPL-3.0-or-later (upstream's Apache-2.0 notices kept).
