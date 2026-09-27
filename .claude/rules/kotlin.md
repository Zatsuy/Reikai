---
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# Kotlin and Android conventions (upstream Reikai 0.4.0 shape)

Code must look like the code around it and merge cleanly with upstream. When a rule here and the
code disagree, the code on upstream's development branch wins; say so in the commit.

## Screens and state
- A screen is a Voyager `Screen`/`Tab` class (serializable constructor args, no lambdas) whose
  `Content()` resolves an AndroidX `ViewModel`. Pure-UI screens may skip the model and say so.
- A model extends `ViewModel()` directly (no base class) and exposes
  `val state: StateFlow<State>` with `field = MutableStateFlow(...)`.
- Graph-only dependencies: `@Inject` + `@ViewModelKey` + `@ContributesIntoMap(AppScope::class,
  binding = binding<ViewModel>())`, resolved with `metroViewModel<T>()`. A call-site value
  (an id) makes it `@AssistedInject` with an `@AssistedFactory`, resolved with
  `assistedMetroViewModel`. A model resolved with a bare `viewModel<T>()` must not be `private`.
- No DI and no preference reads inside `@Composable`; hoist `remember { context.appGraph }` when a
  composable truly needs a graph value. Coroutines: `viewModelScope.launchIO`/`launchUI`,
  `LaunchedEffect`, never `GlobalScope`; `WorkManager` for work that outlives a screen.

## Dependency injection (Metro)
- Fork classes join the graph with `@Inject`, `@SingleIn(AppScope::class)`, `@ContributesBinding`.
  Fork accessors go in a fork-owned `@ContributesTo(AppScope::class)` interface: never edit
  `mihon/app/di/AppGraph.kt` or `reikai/di/ReikaiGraph.kt` for fork members.
- Deferred construction is a `() -> T` parameter; code without a constructor reads
  `context.appGraph.x`. Never add to `MetroInjektRegistrar` and never use Injekt in new code.

## Where fork code goes
- New code: package `jp.reikai.*` (or a fork Gradle module). Fork strings in fork resources.
- An upstream file edit is a seam: minimal, fenced with `// FORK -->` / `// FORK <--` (or a
  one-line `// FORK: why` above a single changed line; XML: `<!-- FORK: why -->`), and only where
  no new file can do the job (a registration line, a one-line hook call).
- Upstream's own `// RK` markers belong to upstream: never add, move or remove them.

## Build, R8, performance
- Check: `scripts/fork/gw :app:compileDebugKotlin`, then the tests the change touches, then
  `scripts/fork/gw spotlessApply` for formatting. JDK 25 locally.
- Release-type builds are minified, `debug` is not. Metro reflects on nothing, but any
  reflection-based library or `@JavascriptInterface` path needs its keeps; confirm with
  `scripts/fork/gw :app:assembleNightly` before calling it done.
- Performance is a pillar: no work on the main thread that can move off it, no allocation in
  scroll or draw paths, no WebView or engine started before a screen needs it. Measure a claimed
  speed-up on the device; never assert it.
- Domain models are immutable (`val`, non-null ids). Preferences via `PreferenceStore` and typed
  `*Preferences`; fork keys start with `jp_`.
