---
paths:
  - "**/src/test/**"
  - "**/src/androidTest/**"
  - "**/*Test.kt"
---

# Tests

- JUnit 5 with Kotest assertions (`shouldBe`); MockK only at real boundaries (network, disk,
  clock, other apps); prefer real implementations everywhere else.
- Coroutines: `runTest` with a test dispatcher; `runBlocking` only in `androidTest`.
- No `if` or loops in a test: parameterise (`@ParameterizedTest`). Test names state the behaviour.
- A test must be able to fail: check it by breaking the code once (mutation) before trusting it.
  A test that recomputes the expected value the way the code does proves nothing.
- Run the class you touched: `scripts/fork/gw :app:testDebugUnitTest --tests "<FQCN>"`
  (most tests live in `app`), not the whole suite, unless the change is cross-cutting.
- JS that runs in a WebView (the Yomitan stand-in, reader scripts) is tested off-device in Node
  where possible; what only a WebView can prove is checked on the device with Chrome DevTools.
