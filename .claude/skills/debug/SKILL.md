---
name: debug
description: Find and fix a bug from a symptom, feedback loop first - build a command that shows the bug, minimise it, rank falsifiable hypotheses, write the regression test, fix the cause, verify. Use for any bug report, crash, wrong behaviour or failing test.
argument-hint: "<symptom>"
effort: high
---
Symptom: `$ARGUMENTS`. Adapted from mattpocock/skills `diagnosing-bugs` (MIT).

1. **Build a feedback loop first. No command that can show the bug, no fix.** Cheapest first:
   1. a unit test in the module (`scripts/fork/gw :app:testDebugUnitTest --tests <FQCN>`);
   2. a small JVM or Node reproduction (Node for WebView JS such as the Yomitan stand-in);
   3. device logs: `adb logcat -d -v brief | grep -E '<tag>|AndroidRuntime'` after reproducing;
   4. UI state: `adb shell uiautomator dump /data/local/tmp/ui.xml && adb pull /data/local/tmp/ui.xml build/fork-logs/`;
   5. WebView JS through Chrome DevTools (`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`);
   6. last resort, the owner reproduces with exact steps and reports what they see (`/owner-steps`).
2. **Minimise** the reproduction until every remaining step matters.
3. **Hypothesise**: three to five ranked, each falsifiable. Instrument with tagged logs
   (`[DEBUG-<4 hex>]`) and test the top one first.
4. **Regression test** at the right seam, failing before the fix.
5. **Fix the cause** with the smallest change; no refactoring on the side. Remove the debug logs.
6. **Verify** with `/verify`; the commit message states the confirmed cause.

Three failed fixes means the model of the problem is wrong: stop, write down what was ruled out,
and report instead of trying a fourth. A bug in upstream code: fix it as a fenced seam and note it
as an upstream candidate in the roadmap.
