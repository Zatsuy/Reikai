# Performance baseline

Real numbers from the owner's tablet and phone, so every change that could make the app slower is
measured against them instead of guessed at (D-008). `scripts/fork/perf.py` produces them;
`results.jsonl` keeps every saved run, one JSON line per device. The first line per device is the
baseline; each run is compared with it and with the last saved line.

## What is measured

A separate copy of the app, the `benchmark` build (`app.reikai.jp.benchmark`: shrunk by R8 like a
release, profileable, its own data), reads a generated offline library in `/sdcard/ReikaiJPBench`:
300 local manga with covers and 30 Japanese novels, each with five downloaded chapters of about
6,000 characters. No network, no plugins, and the owner's own Reikai JP is never touched. Both
devices get the same library. Runs happen in portrait (rotation is restored afterwards), with the
app compiled against its own profile, as a device is a day after an update.

| Metric | What it is | Watch it for |
|---|---|---|
| `cold_start` | launcher tap to first frame (`am start -W`), what the owner waits through | startup; it rests on the 500 ms minimum splash, so it moves little |
| `library_ready` | process start to the library list on screen (`PerfMarks.libraryReady`) | startup work, library loading |
| `library_pss_mb` | memory (PSS, MB) with the library shown, 3 s after each cold start | memory |
| `library_jank_pct` | share of janky frames flinging the library down and up, 3 passes | scrolling smoothness |
| `chapter_open_first` | the first chapter open after a start, when the reader's code loads | reader startup |
| `chapter_open` | later opens: reader launch to the chapter's text drawn (system "Fully drawn") | chapter loading and layout |
| `reader_pss_mb` | memory (PSS, MB) with the novel reader open | reader memory, later the Yomitan engine |
| `reader_jank_pct` | share of janky frames flinging through a chapter, 3 passes | reading smoothness |

Times are in ms. Lower is better everywhere.

Limits, so no one compares what was never measured:
- **The native novel renderer only** (the default). In the WebView renderer the "Fully drawn" mark
  fires before the page paints, so a `chapter_open` taken there reads too low. The Japanese
  reading mode (Phase 4) adds its own mark before it is compared with anything.
- **Library open is the manga chip at a cold start**, the larger of the two lists; the novel chip
  is not timed.
- **The fork stands in for upstream.** The budget "not slower than upstream" is checked against this
  baseline, the fork at the commit below, which is upstream plus a few seams.
- **A made-up library**, sized like a well-used one, not the owner's own.

## Baseline

Saved 2026-09-27 at commit 3da7f326f (build 0.3.2-benchmark, Android 16 on both): medians of 10
cold starts and 5 chapter opens, p90 in brackets. The noise column is the largest change between
this run and two unsaved repeats the same afternoon (one per device run alone, one side by side).

| Metric | Tablet (Tab S10 FE) | Phone (A54) | Noise |
|---|---|---|---|
| `cold_start` | 739 ms (760) | 954 ms (1004) | 2 % |
| `library_ready` | 531 ms (568) | 753 ms (806) | 4 % |
| `library_pss_mb` | 181 MB (193) | 146 MB (146) | 7 % |
| `library_jank_pct` | 4.2 % (10.5) | 1.2 % (1.7) | 0.5 points |
| `chapter_open_first` | 225 ms | 289 ms | 20 % (one sample) |
| `chapter_open` | 215 ms (236) | 236 ms (276) | 15 % |
| `reader_pss_mb` | 370 MB (383) | 286 MB (288) | 5 % |
| `reader_jank_pct` | 0.3 % (0.8) | 1.4 % (1.4) | 0.5 points |

What the numbers already say:

- **Cold start rests on the splash floor.** Reikai keeps its splash for at least 500 ms after the
  screen is created (`SPLASH_MIN_DURATION`), so `cold_start` barely moves when the app gets
  faster; `library_ready` shows the real startup work.
- **The novel reader doubles memory** (tablet 181 to 370 MB, phone 146 to 286 MB) for one
  6,000-character chapter. Worth a look before the Yomitan engine adds its own WebView.
- **The tablet drops more frames scrolling the library** than the phone (about 4 % against 1 %).
- **The phone was warm** during the baseline (battery at 39 °C and 35 %, draining with its screen
  kept on). Runs earlier that day, cooler and with a simpler warm-up, read about 825 ms for its
  cold start; which of the two made the difference was not established. Runs now record battery
  temperature and level, so compare phone numbers taken in a similar state.

## Running it

```
scripts/fork/gw :app:assembleBenchmark       # the app under test; one Gradle build at a time
scripts/fork/perf.py setup                   # once per device (installs, storage folder, library)
scripts/fork/perf.py run                     # measure every connected device, compare with the saves
scripts/fork/perf.py run tablet --save       # record a new reference point once a change has landed
```

A run takes about five minutes per device. **Agents run it** for any item that touches startup,
the library, the readers or an app-wide dependency, and after an upstream merge when the devices
are connected anyway; never as a step for the owner. A difference inside the noise column is not a
result. `--save` refuses runs with uncommitted code, an APK older than the code, or lost samples.
Bump `FIXTURE_INFO["version"]` in the script whenever the generated library changes, since runs
compare only against runs on the same library; `setup --fresh` then rebuilds it on the devices.

## What it leaves on the devices

A second "Reikai JP" icon (the benchmark copy, package `app.reikai.jp.benchmark`, allowed to post
notifications and exempt from battery optimisation) and a `ReikaiJPBench` folder with the test
library. Both are safe to ignore; do not read in that copy. Uninstalling it and deleting the folder
removes everything, and `perf.py setup` puts it back.
