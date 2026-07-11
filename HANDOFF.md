# Handoff — 2026-07-11

Session summary for tomorrow's pickup. M4 is DONE and committed; M5 (Discovery) is fully
researched with a draft engine brief below, zero implementation started.

## What shipped today (all on master, both suites green after every commit)

- `2b7a37a` — Browse reloads installed sources on entry (fixes "installed an extension,
  Browse shows nothing"); roadmap gained M5 Discovery + M6 quick nav, online palette tab
  moved to §12 backlog.
- `f8773a6` — M4.4a: Settings screen (theme picker, applies live, persists via
  `Settings.theme`); download selection All/Unread/Range in DetailsScreen backed by new
  `LibraryRepository.readChapterIds`. **Plus a milestone discovery:** `:app:jvmTest` had
  silently run zero JUnit4-style Compose tests since M3 — the JUnit5 platform discovers
  nothing in `uiTestJUnit4` classes without `junit-vintage-engine`. Now catalog-pinned;
  39 app tests actually execute (previously ~15 across 5 kotlin-test classes).
- `2ec7f65` — M4.4b: cross-extension Search tab. Parallel fan-out over enabled sources,
  per-source error isolation (Cloudflare-gated source shows Solve while others render),
  source filter chips, shell-hoisted state.
- `e0b8cf7` — M4 exit. Opus `/code-review` over the M4.4 diff confirmed 13 defects; all
  fixed: untrusted-data lazy keys removed (duplicate mangaId from a hostile bundle crashed
  composition — fixed in Search AND Browse), guarded Search source load, search cancels
  the prior fan-out + try/finally on the spinner + skips empty source set, sections render
  what was searched rather than live chip state, solving flag is per-source (one solve at
  a time stays deliberate — one embedded browser), post-solve refetch hits only the solved
  source (polite-scraper rule), range downloads normalize reversed bounds (+ regression
  test), empty download selections no longer side-effect the library. PLANNING.md M4 →
  DONE. Live smoke green: full read path against FlameComics (100 results, 79 chapters,
  11 pages), library seeded.

## Decisions taken (owner-visible)

- Search results grouped per source in LazyRows; details opened from Search go back to
  Library (no `fromSearch` nav case yet — recorded as an M4 ceiling in PLANNING.md §9).
- One Cloudflare solve at a time is deliberate (single embedded browser); only the
  labeling is per-source.
- Browse's own unguarded source-list load is left as-is: Browse is replaced wholesale in
  M5 (ceiling noted in PLANNING.md).

## Nothing was blocked

Screen lock never interfered: the screenshot harness renders offscreen, so settings and
search screenshots were captured and visually reviewed (`app/build/screenshots/`).

## M5 Discovery — research findings (the epic research)

Two research passes: R1 read the four pretty-printed fixture bundles
(`tools/bundle-pretty/`), R2 read `@paperback/types` npm tarballs (0.8.7 and
1.0.0-alpha.92) plus the `paperback-toolchain` repo. They converge; every claim below is
evidence-backed in those sources.

**The contract is the 0.9/1.0-alpha pull API, not the 0.8 callback API.** All four fixture
bundles (FlameComics, MangaBat, Toonily, WebtoonXYZ) implement:

- `async getDiscoverSections()` → array of stub descriptors `{id, title, type}` where
  `type` is numeric `DiscoverSectionType` (featured=0, simpleCarousel=1,
  prominentCarousel=2, chapterUpdates=3, genres=4). No items in the stubs.
- `async getDiscoverSectionItems(section, metadata)` → `{items, metadata}` for that one
  section. Paging = call again with the returned metadata. **Must pass the whole section
  descriptor back, not just its id** — bundle parsers read `section.type` to pick the item
  shape.

There is no `getHomePageSections`, no `sectionCallback`, no `getViewMoreItems`, and no
`containsMoreItems` anywhere in the four bundles. (Terminology note: "0.9" ships on npm as
`@paperback/types@1.0.0-alpha.*`; there is no 0.9.x package version.)

**Item shapes** are a discriminated union keyed by section type: `simpleCarouselItem` /
`featuredCarouselItem` / `prominentCarouselItem` (all carry mangaId, title, imageUrl,
optional subtitle), `chapterUpdatesCarouselItem` (adds chapterId, publishDate), and
`genresCarouselItem` (carries a searchQuery, **no mangaId**).

**Capability flags are unreliable.** The `capabilities` metadata object isn't reachable
from the executed bundle's export (it's a local variable), and FlameComics implements the
full API while declaring nothing. Duck-type on the method's presence, exactly as the shim
already does elsewhere (`canInvokeMember`). Bit value 4 = `DISCOVER_SECTION_PROVIDING`
(0.8 name `HOMEPAGE_SECTIONS`) confirmed but useless in practice.

**No new host bindings needed.** The discover paths use `Application.scheduleRequest` +
`arrayBufferToUTF8String` / `decodeHTMLEntities`, all already implemented. Note:
`ApplicationHost.kt:138-152` already has `registerDiscoverSection` et al. — dead
scaffolding, unused by every bundle; do not build on it.

**Paging termination diverges per bundle** (the compat risk): MangaBat returns
`metadata: undefined` on the last page; Toonily/WebtoonXYZ never signal exhaustion
(always `{page: n+1}` — a metadata-only loop pages forever); FlameComics never paginates
(single cached JSON fetch, always `metadata: undefined`). Any pagination loop must also
stop on `items.length == 0` plus a page cap.

**0.8 compat wrapper (if true 0.8 bundles ever load):** the official wrapper translates
callback→pull for the host, so the pull API is the right host surface either way. Wrapper
quirks: it collapses all 0.8 layout types to simpleCarousel, does NOT dedupe stub-then-fill
callback invocations (duplicate section ids reach the host — dedupe by id host-side), and
Paperback labels the whole compat path experimental.

## Draft M5 chunk (a) engine brief (decision-maker synthesis, ready to refine + dispatch)

Locked decisions:
- Domain (`core/.../domain/`): `data class HomeSection(val id: String, val title: String,
  val items: List<MangaEntry>)` — drop the layout `type` from the domain for v1 (UI renders
  every section as a row today; add a layout enum when the UI differentiates). Items
  normalize to the existing `MangaEntry` (mangaId, title, imageUrl→cover). Skip
  `genresCarouselItem` entries (no mangaId to open) and note the skip. `chapterUpdates`
  items keep only the MangaEntry fields.
- `MangaSource` gains `suspend fun getHomeSections(): List<HomeSection>` (default: empty).
  Implementation in `PaperbackExtension`: duck-type — if the source object lacks
  `getDiscoverSections`, return emptyList(). Else `invokeAwaitJson("getDiscoverSections")`,
  then per stub `invokeAwaitJson("getDiscoverSectionItems", fullSectionProxy, null)` —
  first page only, no pagination in v1 (termination divergence above). Same
  new-context + `initialise()` + normalization-at-call-site shape as `getSearchResults`
  (`PaperbackExtension.kt:30-56`), malformed data → `ExtensionDataException`.
- `CatalogRepository` gains `homeSections(sourceId)` passthrough (same pattern as
  `search`).
- Tests: engine tests against all four fixtures with the existing mock-HTTP pattern —
  FlameComics (JSON responses), MangaBat/Toonily/WebtoonXYZ (HTML). Assert: sections
  parse with ids+titles, items normalize, genres items dropped, a source without the
  method yields emptyList, malformed item → ExtensionDataException.
- Mandatory Opus review (shim + `MangaSource` change).

Chunk (b) UI (brief later, after (a) lands): Browse → one tab per installed source;
sections as titled LazyRows of CoverCells; sources without sections fall back to current
search-results view; reuse the reload-on-entry + per-source error isolation patterns from
SearchScreen.

## Next steps (tomorrow)

1. Refine + dispatch M5 chunk (a) engine brief above (Sonnet).
2. Opus review at the chunk boundary (mandatory — shim).
3. Chunk (b) tabbed Browse UI.
4. Then M6 (local search-everywhere palette + reader auto-scroll) per PLANNING.md §9.
