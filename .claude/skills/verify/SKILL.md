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
| Formatted | `scripts/fork/gw spotlessApply`, then `git diff --stat` shows only intended files |
| R8-safe (new package, reflection, JS interface) | `scripts/fork/gw :app:assembleNightly` succeeds |
| Schema intact (after a merge) | `scripts/fork/gw :data:verifySqlDelightMigration` |
| Seams clean | `scripts/fork/seams.py --check` |
| Harness intact | `python3 .claude/hooks/test_hooks.py` |
| Works on the device | install, open, look (below); a screenshot or UI dump you actually read |
| Faster | before and after numbers from the same measurement on the device |
| A subagent did it | `git diff` shows it |

**On a device** (only when `adb devices -l` lists it; the debug package is `app.reikai.dev` until
the identity item lands, then `app.reikai.jp.dev`). The owner reads on the tablet (`SM_X520`),
so check there first, then the phone (`SM_A546E`). With both connected every `adb` command needs
`-s <serial>` (serials from `adb devices -l`); `installDebug` installs on all of them.
- install: `scripts/fork/gw :app:installDebug`
- open: `adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1`
- look: `adb exec-out screencap -p > build/fork-logs/screen.png`, then Read the image; or
  `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml build/fork-logs/`
- cold start time: `adb shell am force-stop <package> && adb shell am start -W -n <package>/eu.kanade.tachiyomi.ui.main.MainActivity`
  (read `TotalTime`; take the median of five)
- crashes: `adb logcat -d -b crash`

Report what ran and what it showed in a few lines. A failure is reported as a failure, with the
output, never smoothed over.
