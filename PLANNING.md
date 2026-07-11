# Manhwa Reader — Project Planning

A modern, clean, performant manhwa/manga reader for Windows desktop that reuses Paperback
(iOS) 0.9 extensions. Written entirely in Kotlin. The only JavaScript in the project is the
third-party extension bundles themselves, which are executed, never authored.

**Objective rework, 2026-07-10.** Native iOS stopped being a hard goal; the accepted Apple
path (months out, if ever) is a thin client talking to `:core` hosted as a server. That frees
the backend to be JVM-first, which enabled the engine pivot to GraalJS (section 6): Kotlin
holds real handles to extension objects, and the hand-authored JS glue layer is gone.

---

## 1. Vision and non-negotiables

- **Great reader first.** The reading experience is the product. Dark, minimal, webtoon-style.
  Modern, clean, and performant on PC is the bar; everything else is secondary.
- **One language: Kotlin.** UI, engine, and logic are all Kotlin. No JS/TS is written by hand —
  with GraalJS this is literal: the only JS that runs is the extension bundles.
- **Reuse Paperback extensions.** They are maintained JS bundles that already run on iOS's
  JavaScriptCore, so they run in any embeddable JS engine. **Implement the 0.9 SDK's
  `Application` surface as the one host contract; run 0.8 bundles through Paperback's official
  0.8→0.9 compat wrapper** (see section 6). Corpus reality (verified 2026-07-10): Inkdex is the
  0.9 registry (68 sources, rebuilt continuously); Netsky's repos are the 0.8 corpus (~133
  sources). Supporting both mirrors what the real Paperback 0.9 app does.
- **Desktop now; other devices later connect to `:core` as a server.** Windows is the only
  machine today. If/when Apple devices arrive, `:server` (Ktor, wrapping the same `:core`)
  serves a thin client. Embedded-native-on-iOS is no longer a design constraint.
- **One agnostic source contract.** Everything talks to a single `MangaSource` interface.
  Paperback is the first adapter. A Tachiyomi adapter could slot in later untouched.

---

## 2. Architecture: modular monolith (not client/server)

Two modules in one process, with a clean boundary between them. No HTTP hop between UI and core.

```
+-------------------------------------------------------------+
|  :app  (Compose Multiplatform UI)                           |
|  Windows/macOS desktop now -> iOS later. Dark webtoon look. |
|  Depends on :core through a repository interface only.      |
+-------------------------------------------------------------+
                     | suspend fun / Flow (in-process)
+-------------------------------------------------------------+
|  :core  (Kotlin Multiplatform)                              |
|  - Extension engine (QuickJS + Paperback 0.9/0.8 SDK shim)  |
|  - Library, read progress, downloads                        |
|  - SQLite persistence                                       |
|  - Domain model + MangaSource contract                      |
|  Knows nothing about how it is hosted.                      |
+-------------------------------------------------------------+
                     |
     Paperback 0.9 + 0.8 extension bundles (untrusted JS)

           (future, optional)
+-------------------------------------------------------------+
|  :server  (JVM only, Ktor)  -- NOT built now                |
|  Also depends on :core, exposes it over HTTP for            |
|  multi-device sync. App and server are two hosts of :core.  |
+-------------------------------------------------------------+
```

**Why not split into two running processes.** The embedded engine is the whole reason iOS can be
native and server-less. Putting HTTP between UI and core throws that away and forces a hosted
server on Apple. So the split is at the module boundary, not the process boundary.

**Why the boundary still matters.** Because `:core` is host-ignorant, adding a `:server` later is a
thin wrapper with zero rewrite. The door to client/server stays open for free. This is a deliberate
"keep both options" stance, not fence-sitting: the isolated `:core` is the one decision that makes
either future cheap.

---

## 3. Tech stack

All Kotlin, all first-party or well-established, all Kotlin Multiplatform capable (except the
optional JVM-only server). Versions below are the latest as of July 2026; pin them in a Gradle
version catalog (`libs.versions.toml`) and let Renovate/Dependabot bump them.

| Concern | Choice | Version (Jul 2026) | Notes |
|---|---|---|---|
| Language | **Kotlin** | **2.4.0** | latest stable (Jun 3 2026); 2.4.20 is EAP only |
| UI | **Compose Multiplatform** | **1.11.0** | Skia-rendered; desktop is the target that matters |
| JS engine | **GraalJS** (`org.graalvm.polyglot`) | **25.1.x** | JVM polyglot: real object handles from Kotlin, promise interop, strong sandbox controls. Replaced quickjs-kt 2026-07-10 when JVM-only became acceptable. |
| HTTP | **Ktor Client** | **3.5.1** | JetBrains, multiplatform |
| Persistence | **SQLDelight** (chosen over Room, 2026-07-10) | latest via catalog | typesafe SQL, all targets |
| Serialization | **kotlinx.serialization** | latest via catalog | match the Kotlin 2.4.0 line |
| Async | **kotlinx.coroutines** | latest via catalog | match the Kotlin 2.4.0 line |
| Image loading | **Coil 3** | latest 3.x via catalog | Compose Multiplatform, memory + disk cache |
| DI | manual constructor injection (chosen 2026-07-10) | — | revisit only if wiring pain appears |
| Server (future only) | **Ktor Server** | 3.4.x | JVM-only module; lighter than Spring for wrapping :core |

> **Engine history.** M0 was built and proven on `quickjs-kt` 1.0.5 (the only KMP-native-capable
> engine, chosen when embedded iOS was a hard goal). It worked, but everything JS-side had to be
> phrased as evaluated strings, which made the engine layer ugly. When iOS-native was dropped
> (2026-07-10), the engine moved to GraalJS on the JVM. Short-lived context-per-call lifecycle
> is retained. Pins live in `gradle/libs.versions.toml`.

**No Spring anywhere in the app.** Not a taste call: Spring is JVM-only and cannot compile to
Kotlin/Native, so it cannot live in `:core`, which must reach iOS. Spring knowledge still transfers
directly (layered services, repository interfaces, dependency inversion). Only the library names
change. Spring Boot could run in a future `:server`, but Ktor Server is the better-fitting tool
there and shares the coroutines/serialization stack.

---

## 4. Module boundary (the contract the UI sees)

The UI never touches the engine or the DB directly. It talks to repositories in `:core`.

```kotlin
// in :core, consumed by :app
interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryItem>>
    suspend fun addToLibrary(sourceId: String, mangaId: String)
    suspend fun progress(chapterId: String): ReadProgress?
    suspend fun setProgress(chapterId: String, page: Int)
}

interface CatalogRepository {
    suspend fun installedSources(): List<SourceInfo>
    suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry>
    suspend fun details(sourceId: String, mangaId: String): MangaDetails
    suspend fun chapters(sourceId: String, mangaId: String): List<Chapter>
    suspend fun pages(sourceId: String, chapterId: String): List<Page>
}
```

Same discipline as the engine being ignorant of its host, now applied to the whole core.

---

## 5. Domain model

Normalize every source's messy shape at the engine boundary into these.

```kotlin
data class MangaEntry(
    val sourceId: String,
    val mangaId: String,
    val title: String,
    val cover: String? = null,
)

data class MangaDetails(
    val entry: MangaEntry,
    val authors: List<String>,
    val description: String? = null,
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val tags: List<String> = emptyList(),
)

data class Chapter(
    val chapterId: String,
    val number: Double,
    val title: String? = null,
    val language: String? = null,
    val publishedAt: Instant? = null,
)

data class Page(
    val index: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(), // referer etc.
)

// the agnostic port. Paperback is the first implementation.
interface MangaSource {
    val sourceId: String
    suspend fun search(query: String, page: Int): List<MangaEntry>
    suspend fun getDetails(mangaId: String): MangaDetails
    suspend fun getChapters(mangaId: String): List<Chapter>
    suspend fun getPages(chapterId: String): List<Page>
}
```

Keep infrastructure out of these types. No HTTP, no DB, no framework leakage.

---

## 6. Extension engine (the risk lives here)

- **One host surface, one SDK generation: the 0.9 `Application` API.** Decision 2026-07-10:
  0.9 extensions ONLY. Inkdex builds 68 sources against `@paperback/types 1.0.0-alpha.92`,
  rebuilt continuously — corpus enough. We implement the 0.9 `Application` global in Kotlin
  (reference: `packages/types/src/impl/Application.ts`, branch `0.9` of
  `Paperback-iOS/paperback-toolchain`; member-by-member audit in docs/application-surface.md).
  Types pin: `1.0.0-alpha.92`.
- **0.8 compat was built, then cut (2026-07-10).** A working spike lives on branch
  `archive/0.8-compat`: the official compat wrapper vendored as a 55 KB esbuild IIFE from the
  `@paperback/types` npm tarball, cheerio bundled for host injection, a Compat08Runtime load
  path, plus two hard-won facts — the wrapper's ownKeys-less JS Proxy false-positives Graal's
  interop assertions under `-ea`, and the madara 0.8 corpus sits behind Cloudflare (two
  sources 403'd live validation). Resurrect from that branch if 0.8 ever earns its place;
  do not rebuild from scratch.
- **Extension source to pull from.** Inkdex 0.9: base URL
  `https://inkdex.github.io/extensions/0.9/stable`, serving `versioning.json` plus
  `<SourceId>/index.js` per source. M0 target: FlameComics (28 KB, JSON API, no cheerio).
- **Prior art to study first (do not skip this).** `dokar3/any` is a multi-source reader built on
  JavaScript extensions + Jetpack Compose, by the same author as `quickjs-kt`. It is a working
  reference for the exact pattern in this plan: wiring a JS engine to a Compose reader with a
  pluggable source format. It uses its own source format rather than Paperback's, and leans Android
  Compose rather than full Multiplatform, but the architecture is the blueprint. Read it before
  writing the engine.
- **GraalJS, typed handles, zero glue JS.** The polyglot API gives Kotlin real references to the
  extension objects: methods are invoked with `invokeMember`, promises bridge to coroutines, and
  the `Application` host surface is a Kotlin `ProxyObject` (unknown members throw named errors
  from Kotlin, not from a JS Proxy trap). No prelude, no expression strings, no mailbox.
- **Reconstruct the Paperback SDK surface in Kotlin.** The extensions expect the `Application`
  host object. It is implemented entirely in Kotlin and bound into the context. The surface was
  mapped empirically in M0 against FlameComics: scheduleRequest, get/setState, Selector +
  SelectorRegistry, registerInterceptor, getDefaultUserAgent, sleep, arrayBufferToUTF8String,
  and the rest of `impl/Application.ts` lands in M1.
- **cheerio runs inside QuickJS.** 0.9 bundles are fully self-contained: sources that scrape HTML
  inline their own cheerio (verified against real bundles), and the host provides no parser.
  0.8 bundles loaded via the compat wrapper may expect a constructor-injected cheerio — resolve
  empirically in M1.
- **Engine lifecycle: fresh instance per call.** One short-lived GraalJS context per extension
  call, closed in `finally` (mirrors `dokar3/any`). Extension state persists host-side behind
  `getState`/`setState`, so nothing is lost between instances.
- **Network is a host binding.** The extension request manager is backed by Ktor Client, so all
  network (headers, cookies, later Cloudflare handling) is Kotlin you control.
- **Runs off the UI thread.** `quickjs-kt` is coroutine-integrated; extension calls run on a
  background dispatcher and never block scrolling.

**Hard security invariant:** an extension gets **no** capability except the injected request
manager. No filesystem, no arbitrary network, no process access. This is enforced in every review.

---

## 7. UI and theming

- **Dark, minimal, webtoon-first.** Reference: **Mihon** (open-source manga reader, Jetpack Compose,
  ships a pure-black AMOLED theme). Same domain, same look, same toolkit.
- **Theme from one place.** `MaterialTheme` wrapping a `darkColorScheme(...)` at the root; every
  component follows it. Swapping/adding themes is swapping that object.
- **Custom themes are first-class.** For a look that is not "Material default," build a design system
  with `CompositionLocal` tokens (color, type, spacing) and drop Material. A custom palette (e.g.
  Kanagawa Dragon) becomes the default scheme in ~10 lines.
- **The reader surface is custom regardless.** A `LazyColumn` long-strip with immersive mode: near-
  black background, hidden system bars on mobile / borderless-fullscreen window on desktop, controls
  that fade in and out. Its "theme" is mostly the background color behind the pages.
- **Desktop vs mobile: shared look, tuned input.** Rendering and theming are write-once. Interaction
  differs: wheel/trackpad scroll and key paging on desktop, fling and tap zones on mobile. Small,
  real, per-platform work.

---

## 8. Performance (the "scroll like Discord" goal)

The JS engine is **not** the bottleneck. Extension calls are network-bound, so cache them; QuickJS
interpreter speed is noise. Scroll smoothness is a rendering + image-pipeline problem, solved in
Kotlin:

- `LazyColumn` for the strip: virtualizes so only visible/near items compose. Stable keys.
- **Coil 3** with memory + disk cache.
- **Prefetch** images for pages and the next chapter just ahead of scroll position, during idle.
- **Decode off the main thread**, downsample to viewport width (do not push 4000px bitmaps through
  the compositor).

Compose renders through Skia and is native-compiled; Discord is Electron, so native Compose can
match or beat it.

---

## 9. Build order (milestones)

De-risk the unknown before building around it.

- **M0 — Engine spike (the whole ballgame).** A Kotlin test that loads *one* real Paperback extension
  (Inkdex FlameComics, a 28 KB 0.9 JSON-API bundle) through `quickjs-kt` 1.0.5, runs its search,
  and returns normalized `MangaEntry`. No UI, no DB. Kotlin 2.4.0 + quickjs-kt compatibility was
  verified 2026-07-10. Task-level breakdown lives in the implementation plan.
- **M1 — Engine hardened.** Details, chapters, pages for that source. Full `Application` surface
  with an audit table. 0.8 bundles running via the official compat wrapper. Sandbox in place.
  `MangaSource` finalized. Two or three more extensions loaded to shake out shim gaps.
- **M2 — Core. DONE 2026-07-10.** SQLDelight 2.3.2 (library, progress, source registry, cookie
  jar, downloads); `LibraryRepository`, `CatalogRepository` (registry owns the pinned bundle
  sha256), per-source `CookieStore` wired into `ApplicationHost`, `FileDownloadManager`.
  Headless, driven by tests. Not yet wired: production composition (a real `SqlCookieStore`
  into `ExtensionRuntime`'s host, file-backed driver) lands with `:app` in M3. Download
  ceilings recorded in §10.
- **M3 — Reader app. DONE 2026-07-10.** Compose Multiplatform 1.11.0 desktop on Windows:
  Kanagawa Dragon theme, library grid, browse/search, details, downloads screen, and the
  immersive long-strip reader (1000dp-capped centered strip, controls auto-hide, keyboard
  paging, fullscreen, debounced progress persistence). Production composition proven by a
  guarded live smoke (`LiveSmokeTest`, `$env:MANGO_SMOKE="1"`) + real-window screenshot.
  Loop gained UI verification: offscreen screenshot harness (`Screenshots.render` →
  `app/build/screenshots/`) reviewed visually per chunk, plus uiTestJUnit4 flow tests.
  Ceilings: text glyphs on the nav rail (no material-icons artifact resolves for CMP 1.11 —
  pick an icon strategy in M4), reader title shows chapter id, next-chapter prefetch not
  built, downloads bypass host policy (§10).
- **M4 — Self-sufficient app. DONE 2026-07-11.** Offline reading from downloads;
  extension install/update from the Inkdex 0.9 repo (schema v3: version + per-source UA);
  Cloudflare solve via embedded Chromium (jcefmaven) harvesting cf_clearance into the
  per-source cookie jar; settings page (theme picker, live apply), download selection
  (all/unread/range, reversed bounds normalized), cross-extension search — a dedicated
  Search tab: parallel fan-out over enabled sources, per-source error isolation, sections
  keyed to what was actually searched, single-source refetch after a Cloudflare solve.
  Exit: Opus review over the M4.4 diff (13 confirmed findings, all fixed same day), both
  suites green, live smoke green (full read path against FlameComics). Milestone discovery:
  `:app` had silently run zero JUnit4-style Compose tests since M3 (missing
  junit-vintage-engine — now catalog-pinned; 39 tests execute). Ceilings: Details opened
  from Search returns to Library (no fromSearch case); Browse's own source-list load is
  still unguarded against a registry read failure (resolved in M5(b): guarded load with a
  user-visible error state).
- **M5 — Discovery: extension home pages (planned 2026-07-11; researched same day; DONE 2026-07-11).**
  Browse becomes one tab per installed extension rendering that source's own homepage
  sections ("Most Recent", "Popular" — whatever the extension publishes). Research
  (2026-07-11, fixtures + @paperback/types source) corrected the contract: all four fixture
  bundles use the 0.9/1.0-alpha **pull API** — `getDiscoverSections()` returns stub
  descriptors `{id, title, type}` (numeric DiscoverSectionType), then
  `getDiscoverSectionItems(section, metadata)` per section returns `{items, metadata}` —
  NOT 0.8's `getHomePageSections(sectionCallback)`. No new host bindings needed (discover
  paths use scheduleRequest like search). Capability flags are unreliable (FlameComics
  implements the API but declares none): duck-type on the method, don't gate on intents.
  Pass the whole section descriptor back into getDiscoverSectionItems (parsers read
  section.type). Per-section paging termination diverges per bundle (undefined-metadata vs
  never-signals vs never-paginates) — v1 fetches first page only. Two chunks: (a) engine —
  invoke the pull API through the existing invokeAwaitJson shape, `MangaSource` gains a
  home-sections method + domain `HomeSection` type, verify against all four fixtures; shim
  work, mandatory review. (b) UI — tabbed Browse, one tab per installed source, horizontal
  section rows; sources without the method fall back to the plain search view. Full
  research + draft engine brief: HANDOFF.md (2026-07-11). Sequenced before the
  design-refinement pass so that pass styles the real Browse, not a throwaway.
  **Chunk (a) DONE (2026-07-11).** Domain `HomeSection`; `MangaSource.getHomeSections`
  (default empty) + `CatalogRepository.homeSections` (abstract — deliberate, a repo default
  would mask a forgotten override); `PaperbackExtension` invokes the pull API duck-typed,
  first page only, original stub Value passed back. Chunk-boundary review confirmed 10
  findings, 9 accepted + fixed: stub loop capped at 32 (hostile-bundle DoS), three
  unwrapped crash paths on malformed stubs/types/results now named ExtensionDataException,
  genres-skip moved before dedupe so a genres stub can't swallow a real section's id, plus
  test-oracle and duplication cleanups; rejected: repo-interface default. Replay fixtures
  recorded for FlameComics/MangaBat/Toonily; WebtoonXYZ excluded (Cloudflare-walled, has no
  recorded fixtures anywhere — challenge coverage stays in LiveDetectWebtoonXyzTest). Note:
  the FlameComics homepage fixture is shared across LiveRecord suites; on buildId rotation
  re-record them together (documented in LiveRecordDiscoverTest).
  **Chunk (b) DONE (2026-07-11).** Browse: existing source chips act as tabs; per-source
  discover sections load lazily on tab selection (session cache, error retried on
  revisit), rendered as titled cover shelves; search-on-submit overlays a results grid
  tagged by source (fixes the old stale-results-across-chips behavior), blank submit
  returns to sections; challenge state split per mode with click-time mode capture for
  Solve; guarded source-list load with honest empty/error states. Review confirmed 10
  findings, 9 fixed (search job cancellation, per-mode challenge state, click-time solve
  dispatch, captured query, pending-guard race, empty/error states, cache+solve tests).
  Ceilings: a solve is cancelled if the user leaves the screen mid-solve, and blank-query
  submit is the only way back from results to sections (no clear button). RESOLVED (R4,
  2026-07-11): one-solve-at-a-time is now enforced app-wide by SingleFlightChallengeSolver
  (Mutex.tryLock decorator over JcefChallengeSolver, wired in AppGraph; a concurrent solve
  returns false and the second screen's button just re-enables).
- **M6 — Quick nav and reader QoL (planned 2026-07-11).** (a) Search-everywhere palette,
  IntelliJ double-Shift style, **local only**: a modal that fuzzy-matches app screens,
  settings entries, and library/downloaded manhwa; Enter navigates straight to the hit.
  Built with a tab bar from day one so the online tab (§12 backlog) slots in later without
  rework. (b) Configurable auto-scroll in the reader: hotkey-bound toggle, scroll speed
  configurable in settings. After M6: UI/functionality refinement phase (owner call).
  **Chunk (a) DONE (2026-07-11).** Double-Shift palette (Window-level detector requiring a
  Shift release between taps — held-Shift auto-repeat can never trigger), text-only rows
  (owner requirement, no images/Coil in the palette), all colors from the theme (owner
  requirement, scrim uses colorScheme.scrim). Extension contract: PaletteHit/PaletteProvider/
  PaletteTab + one registration point (paletteTabs) — new searchable feature = one provider
  + one line; new mode = a new tab (v1: All/Manhwa/Actions). Candidates fetched once per
  open/tab-switch, ranked per keystroke in memory by central fuzzyScore (multi-start greedy
  anchoring; full-DP alignment is the recorded escape hatch if ranking complaints appear).
  Review confirmed 10 findings, all fixed (auto-repeat chord, overlay-level key handling,
  reader focus restore on close, stale-hit flash, scroll-into-view, greedy anchoring,
  test honesty, fetch-per-keystroke). Ceilings: later query chars still match greedily;
  online tab will need a per-tab fetch policy (query passed live); palette-opened Details
  goes back to Library (nav-origin refactor still pending).
  **Reader infinite scroll DONE (2026-07-11, owner-reported bug: no way to reach the next
  chapter).** The reader is now a multi-chapter strip: `Screen.Reader` carries the sorted
  chapter list from Details, chapters load as segments (downloads-first per segment,
  divider rows between chapters, end-of-series footer), the next chapter auto-appends when
  the strip nears its end (single in-flight load, inline Retry/Solve row on failure —
  initial-chapter failure keeps the full-screen flow), progress writes are per-current-
  segment (which also marks chapters read), N/Next jump forward, P/Prev re-anchor.
  Opus-reviewed (guided single-reviewer): 0 blockers; duplication and test-assertion NITs
  fixed in-session. Ceilings: no upward prepend — P re-anchors instead (LazyColumn prepend
  scroll-anchoring is the escape hatch if seamless backward reading is ever wanted);
  the progress snapshotFlow rebuilds the flattened row list every scroll frame (O(loaded
  pages) — emit the raw index and compute after the debounce if huge strips ever appear).
  RESOLVED (R4, 2026-07-11): the one-frame stale-offset jump on P — listState is now a
  fresh LazyListState keyed on the anchor; chapter-nav helpers stop auto-scroll themselves
  (review finding: the overlay Prev button used to strand the drive loop on the detached
  state).
  **Chunk (b) DONE (2026-07-11) + owner-reported controls bug.** A key toggles auto-scroll
  (frame-clock loop, dt × speed, suspend scrollBy); speed is Settings.autoScrollSpeed
  (Float dp/s, default 120, slider 30–600 on the Settings screen, persisted only on
  drag-finish), hoisted Main → AppShell like the theme. Manual paging/N/P/Escape stop it.
  At the hard end of loaded content it keeps waiting while the next chapter is loading
  (resumes when it appends — infinite reading), stops for real at the last chapter or a
  failed append. Controls overlay now reveals only on hover within 80dp of the top edge
  (CONTROLS_REVEAL_BAND) or on click, then auto-hides as before. Opus-reviewed (guided
  single reviewer): 0 blockers; wait-while-loading stop condition and named band constant
  applied from findings. Ceilings: a cursor parked in the top band re-reveals controls on
  each auto-hide (emergent from synthetic hover moves, reads as intended hover-to-pin
  behavior). RESOLVED (R4, 2026-07-11): auto-scroll now pauses while the palette overlay
  is open (paletteVisible in the drive loop's keys/guard) and auto-resumes on close.
- ~~M5+ — Apple targets~~ moved to §12 backlog (2026-07-11): not needed now or anytime soon.

---

## 10. Known risks and hard rules

- **The SDK shim is the risk.** Paperback's runtime contract is discovered empirically, not handed
  to you. Pin to 0.8, expect iteration. Keep a tight human loop here.
- **Untrusted code execution.** Extensions are third-party JS. Invariant from section 6 is enforced
  every review.
- **Be a polite scraper.** Cache aggressively, rate-limit per source. Faster app, no IP bans.
  Personal, local tool; treat source sites with restraint.
- **Moving target.** Paperback keeps compatibility roughly one version behind and 0.9 has been a long
  beta while 0.8 stays the shipping SDK. Building the shim to 0.9 but loading 0.8 bundles hedges
  both: forward-compatible, but working today. Pin `@paperback/types` and re-verify when 0.9 ships.
- **Downloads bypass extension interceptors and host policy (M2.4 ceiling).** Images are
  fetched by the app with `Page.headers` only: sources that sign image URLs in interceptors
  will 403 (route through host interceptors when a real source needs it, M3+), and the
  download client skips `ApplicationHost`'s per-host rate limit — when the project-wide host
  allowlist lands, the download path must go through the same policy as `scheduleRequest`.
- **Cloudflare (built M4.3, mirrors Paperback's manual-check flow).** Detection lives in
  `:core`: `ApplicationHost` turns a Cloudflare-challenge response into a domain
  `ChallengeRequiredException` carrying the URL (M4.3a). Solving lives in `:app`: an embedded
  Chromium via **jcefmaven** (`me.friwi:jcefmaven` — NOT KCEF, which was archived Oct 2025 and
  its author says don't use it; the spike proved jcefmaven renders, passes CF, and reads the
  HttpOnly `cf_clearance` back via `CefCookieManager.visitAllCookies`, which a lightweight
  WebView2 wrapper could not) opens the challenge, the user passes it (often invisibly), and
  the harvested `cf_clearance` (+ `__cf*`) goes into the per-source cookie jar (M2.3). The
  clearance cookie is UA-bound, so the solver pins a fixed desktop-Chrome UA on both the CEF
  browser and the source (`setUserAgent`, which evicts the cached engine). CEF natives download
  into `<dataDir>/jcef` on first solve (~100 MB, one time). **Header-name casing is part of
  the fingerprint** (found live 2026-07-11): bundles write h2/iOS-style lowercase names
  (`user-agent`), and replayed verbatim over Ktor CIO's HTTP/1.1 they get challenged even
  with valid cf_clearance + matching UA — byte-identical request passes title-cased.
  `ApplicationHost.canonicalHeaderName` normalizes at the wire boundary; keep it if the
  HTTP client ever changes (and revisit if that client speaks h2, where lowercase is law). Faithfulness gaps to close later:
  the 0.9 SDK expects the host to call the extension's `cloudflareBypassCompleted(request,
  cookies, localStorage)` callback (we use the shared jar instead), and some CF flows also
  persist state in localStorage (we harvest cookies only).

---

## 11. Claude Code workflow (the loop)

**Lean loop, model-tiered (adopted 2026-07-10 after the M1 retro; operative rules live in
`CLAUDE.md`, this section is the rationale).** The four-role orchestration was retired: on a
~2k-line codebase the briefing and handoff overhead exceeded the work itself, and M1's
serial dependency chain gave the roles nothing to parallelize.

The economics that replaced it: spend big-model tokens on low-volume, high-leverage work
(decisions, review) and cheap tokens on high-volume work (writing code and tests).

| Tier | Role |
|---|---|
| Fable 5 (session) | Architect and decision maker: milestone plans, task briefs, arbitrates review findings. Writes no bulk code. |
| Opus | Reviewer: `/code-review` at chunk boundaries against the `CLAUDE.md` invariants. |
| Sonnet subagents | Implementers: execute a short brief (locked decisions, files, acceptance criteria), code + tests. |

Guards learned in M1:
- Never dispatch discovery to the implementer. Research subagents may discover, but their
  findings report back to the decision maker and get reviewed before entering a brief —
  M1's briefs were expensive precisely because the implementer contracts had to contain
  the discovery.
- Dispatch per chunk (M2.1-sized), never per task, so briefing overhead amortizes.
- Scope gate is a habit, not a stage: one sentence re-validating the chunk against the
  owner's latest stated objectives.
- Review is mandatory for sandbox/`scheduleRequest`/normalization diffs, skipped for schema
  and repository plumbing. The orchestrator re-runs verification, commits, and reports at
  milestones; no per-task human gate.

**Multi-agent fan-out is reserved for wide, mechanical, parallelizable work** — e.g. the M4
extension shake-out (one agent per source against the compat matrix) — and needs explicit
owner approval. Sequential feature-building never qualifies.

---

## 12. Later roadmap (design for it, do not build it yet)

**Dogfood backlog (triaged 2026-07-10, M3.5).** Functionality shipped in M3.5a–c: full-res
pages, downloads metadata + library-on-download + download-all, configurable theme
(`settings.properties`), browse state retention. Still open, in rough priority order:
- reader serves downloaded pages when present (offline reading — downloads are write-only today)
- download selection: range / unread-only (unread = no read_progress row)
- cross-extension search: query all installed sources, results grouped per source
- settings page (theme picker first — the registry and persistence already exist)
- proper UI design pass (icon strategy, spacing/type rhythm, window-resize behavior)
- search-everywhere palette, online tab: a Tab-reachable second tab in the M6 palette that
  runs cross-extension search (reuses the M4.4 machinery, debounced); local and online
  results never mix
- Apple targets (ex-M5, parked 2026-07-11): macOS/iOS thin client against `:core` behind a
  future `:server`; revisit only if an Apple device actually materializes


Add an **enrichment** service behind its own interface. The reader asks "is there an audio/animation
track for this chapter?" and plays it if present. Fully decoupled from the reader core.

- **Storybook / voice-read mode.** Per chapter: feed each page image to a vision-language model to
  get structured `{ order, speaker, text }` (webtoons are linear vertical strips, which helps), then
  synthesize with **ElevenLabs**, mapping speakers to voices, and cache the audio track.
- **Animation.** Much further out; same enrichment slot, same "play if present" contract.

These need cloud compute (VLM + ElevenLabs), but that does **not** force a server: the app can call
those APIs directly with the user's own key and cache locally. A `:server` only earns its place if
you want generated audio shared across devices.

---

## 13. Decisions (resolved 2026-07-10)

- Host model (reworked 2026-07-10): Windows desktop client is the product. Apple/other devices,
  if ever, connect to `:core` behind a future `:server`. Embedded-native iOS dropped as a goal.
- Engine (reworked 2026-07-10): GraalJS on the JVM, replacing quickjs-kt. Driven by the host
  model change plus the readability cost of string-glued JS.
- Persistence: SQLDelight.
- DI: manual constructor injection; revisit only if wiring pain appears.
- Kotlin 2.4.0.
- SDK (final 2026-07-10): 0.9 extensions only, types pinned to `1.0.0-alpha.92`. The 0.8
  compat spike is archived on `archive/0.8-compat`, not on the roadmap.
- Downloads are in v1: manager in M2 core, UI in M3.
- Loop (reworked 2026-07-10 after the M1 retro): model-tiered lean loop from section 11 —
  Fable 5 architects and decides, Sonnet subagents implement per-chunk briefs, Opus reviews
  at chunk boundaries. Discovery never goes to implementers (research subagents report
  back for review before facts enter a brief). Four-role loop retired; multi-agent
  fan-out only for wide mechanical work (M4 shake-out) with owner approval.
