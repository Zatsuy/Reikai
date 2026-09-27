# Reikai JP

A manga and light-novel reader for Android, built for reading in Japanese: look up any word while
you read, add it to Anki in one tap, and read Japanese novels vertically or horizontally with
furigana, the way they are printed.

Reikai JP is a fork of [Reikai](https://github.com/unseensnick/Reikai), which is built on
[Mihon](https://github.com/mihonapp/mihon). Everything Reikai does (manga and light novels in one
library, LNReader plugins, multi-source merge, trackers) is kept and followed as it develops; the
fork adds the Japanese-immersion layer on top.

## Status

In preparation. The fork currently matches Reikai's in-progress 0.4.0 release; the Japanese
features are being built in the order of the [roadmap](../docs/fork/ROADMAP.md). Nothing here is
released yet.

## Planned

- **Dictionary lookup powered by [Yomitan](https://github.com/yomidevs/yomitan)** itself, running
  inside the app: your Yomitan dictionaries, conjugation handling, frequency, pitch accent and
  audio, updated automatically as Yomitan releases.
- **One-tap Anki mining** through AnkiDroid, compatible with Yomitan note setups such as Lapis.
- **A Japanese reading mode** for light novels: vertical or horizontal text, pages or scrolling,
  furigana options, character counts and reading statistics.
- **Local EPUB and text books**, then **manga lookup** from text on the page.

How it is designed: [architecture](../docs/fork/architecture.md). Decisions:
[decisions](../docs/fork/decisions.md).

## Licence

Reikai JP is distributed under the [GNU GPL v3.0 or later](../LICENSE), which it needs in order to
run Yomitan's code. It includes Apache-2.0 code from Reikai and Mihon, whose licence is kept in
[LICENSES/Apache-2.0.txt](../LICENSES/Apache-2.0.txt); files that came from those projects keep
their original notices. Dictionaries, audio and recognition models are never bundled: you supply
your own.

## Credits

[Reikai](https://github.com/unseensnick/Reikai), [Mihon](https://github.com/mihonapp/mihon),
[Yomitan](https://github.com/yomidevs/yomitan), [LNReader](https://github.com/LNReader/lnreader)
and its [plugins](https://github.com/LNReader/lnreader-plugins), and the reference projects listed
in the [research notes](../docs/fork/research/landscape-2026-09.md).
