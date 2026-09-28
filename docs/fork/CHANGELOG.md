# Reikai JP changelog

Changes you will notice in the app, newest first. Upstream Reikai's own changes are in its
[CHANGELOG.md](../../CHANGELOG.md); this file lists only what the fork adds or changes.

## Unreleased

### Japanese: a reader made for Japanese novels
- Japanese novels now open in a new **Japanese reader** that shows the text like a printed book:
  vertical lines read right to left, in a Mincho font, with Japanese line breaking, furigana above
  the kanji, short numbers set upright and emphasis dots beside the text. The first time, a short
  message offers horizontal text instead.
- **Pages or scrolling**, in either direction: swipe (or use the volume keys, when **Volume keys**
  is on in the reader settings) to turn pages; in scrolling mode vertical text scrolls sideways and
  horizontal text scrolls down, to the end of the chapter. Paging or scrolling past the end opens
  the next chapter (chapters no longer join into one long page in this reader).
- Reader settings → **For this series** → **Japanese reader**: Reading (Pages or Scrolling), Text
  direction, Furigana (Show, Dimmed, Hidden, Tap to toggle, Never), Japanese font (Mincho, Gothic
  or a font you added), Tap on text. Text size, line spacing, margins and colours are the ones the
  standard reader uses. Your place is kept to the character when you change any of them or rotate
  the screen; some standard-reader settings (the rendering mode, bionic reading, joined chapters)
  do nothing in the Japanese reader.
- **Switch to standard reader** in the reader's menu (⋮) goes back to the normal reader for that
  novel, and **Switch to Japanese reader** comes back; each novel remembers its choice. Novels from
  Japanese sources open in the Japanese reader unless you switched them.
- **Tap a word to look it up** (tapping anywhere in a word, or on its furigana, finds the whole
  word); tap outside the text for the menu. **Tap on text → Turn pages** makes taps turn pages
  instead, and a long-press still looks up.
- Cards now carry the book's **cover** in Lapis's Picture field, from both readers. If you already
  ran **Set up cards for Lapis**, run it once more to add the cover.
- **Reading statistics** (Settings → Japanese): characters read, reading time and speed for today,
  the last 7 days and all time, per day and per novel, counted the way ttu counts them;
  **Export for ttu** saves a file ttu can import.
- A **status bar** at the bottom of the Japanese reader shows the time, battery, chapter, progress,
  characters read in the chapter and your reading speed (switch it in the reader settings; it is
  off in the standard reader unless you switch it on).
- A chapter that fits on one screen is marked read as soon as it opens (switch in the reader
  settings).
- **Translate chapter** in the reader's menu (⋮) shows the chapter translated, in either reader, and
  **Show original** goes back. Nothing is sent until you tap it. Settings → Japanese →
  **Translation** picks the service: Google (no key, the default), DeepL (your key), or an AI
  service (OpenAI, Gemini, DeepSeek, OpenRouter, Ollama or another, with your key, address and
  model). Keys are not saved in backups. Translations are kept, so opening one again sends nothing.
  In the Japanese reader a translated chapter shows horizontally, without lookup or statistics.

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
