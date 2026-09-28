---
name: verify
description: Prove a change works before claiming it - run the cheapest checks that can fail for this change, read their output, and report evidence, never impressions. Use before every commit, before saying "done", and whenever a subagent reports success.
effort: medium
---
No completion claim without fresh evidence (adapted from obra/superpowers
`verification-before-completion`, MIT). Run the rungs the change needs, cheapest first, one
Gradle build at a time. For several builds, hand them to the `verifier` agent so its output stays
out of your context.

| Claim | Evidence |
|---|---|
| Compiles | `scripts/fork/gw :app:compileDebugKotlin` (or the module's compile task) succeeds |
| Tests pass | `scripts/fork/gw :app:testDebugUnitTest --tests "<FQCN>"` for each class touched; the full suite after an upstream merge |
| Yomitan engine works off-device (`jp-yomitan/`, its stand-in, the bump script) | `scripts/fork/gw :jp-yomitan:testDebugUnitTest`; `node --test jp-yomitan/src/test/js/*.test.mjs`; `python3 -m unittest scripts/fork/test_yomitan_bump.py` |
| Formatted | `scripts/fork/gw spotlessApply`, then `git diff --stat` shows only intended files |
| R8-safe (new package, reflection, JS interface) | `scripts/fork/gw :app:assembleNightly` succeeds |
| Schema intact (after a merge) | `scripts/fork/gw :data:verifySqlDelightMigration` |
| Seams clean | `scripts/fork/seams.py --check` |
| Harness intact | `python3 .claude/hooks/test_hooks.py` |
| Works on the device | install, open, look (below); a screenshot or UI dump you actually read |
| Faster | before and after numbers from the same measurement on the device: `scripts/fork/perf.py run` against the saved baseline ([docs/fork/perf/](../../../docs/fork/perf/README.md)) |
| A subagent did it | `git diff` shows it |

**On a device** (when `adb devices -l` does not list the one you need, ask the owner to connect it
with AskUserQuestion and wait: D-019; the debug package is `app.reikai.jp.dev`; the published
release is `app.reikai.jp`, signed with a key agents never have, so never install a local
`nightly` build over it). The owner reads on the tablet (`SM_X520`),
so check there first, then the phone (`SM_A546E`). With both connected every `adb` command needs
`-s <serial>` (serials from `adb devices -l`); `installDebug` installs on all of them.
- install: `scripts/fork/gw :app:installDebug`
- open: `adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1`
- look: `adb exec-out screencap -p > build/fork-logs/screen.png`, then Read the image; or
  `adb shell uiautomator dump /data/local/tmp/ui.xml && adb pull /data/local/tmp/ui.xml build/fork-logs/`
- speed and memory: `scripts/fork/perf.py run` (benchmark build, fixed library, compared with the
  saved baseline); a quick look on the debug build is `am start -W` and its `TotalTime`
- crashes: `adb logcat -d -b crash`
- done with the device for the session (D-031, the owner's devices are short of space): uninstall
  every agent package (`pm list packages | grep reikai.jp.`: `.dev`, `.benchmark`, `.dev.test`),
  delete what you put on shared storage or in `/data/local/tmp` (test folders such as
  `/sdcard/ReikaiJPBench`, dumps, recordings), set changed settings back (D-023), and say so. Never
  touch the owner's own apps and folders (`app.reikai.jp`, `/sdcard/Reikai`).

Report what ran and what it showed in a few lines. A failure is reported as a failure, with the
output, never smoothed over.
