---
paths:
  - "**/assets/**/*.js"
  - "**/assets/**/*.html"
  - "**/assets/**/*.css"
  - "**/*WebView*.kt"
  - "**/*Bridge*.kt"
  - "**/*Viewport*.kt"
---

# WebViews, bridges and embedded JS

- **Yomitan's vendored files are never edited by hand.** Adaptation lives in the fork's stand-in,
  host and CSS; a new Yomitan version arrives only through the bump script and its tests.
- Bridges (`@JavascriptInterface`, web message listeners) validate the calling origin and a
  per-document token (upstream's `NovelWebBridge` pattern), accept only typed messages, and never
  expose file, network or preference access to page content, which may come from any website.
- `allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` stay off. Serve bundled
  assets from a fixed private https origin (`WebViewAssetLoader` or `shouldInterceptRequest`).
- WebView debugging (`setWebContentsDebuggingEnabled`) only in debug builds. On the device an
  agent inspects pages through `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`.
- Performance: warm a WebView before it is needed and off the critical path; never block the main
  thread waiting on JS; batch bridge calls; keep scroll and tap handlers allocation-free.
- Japanese text: set `lang="ja"`; ruby (`rt`, `rp`) is never counted, scanned or selected as the
  word itself.
