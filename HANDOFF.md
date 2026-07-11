# Handoff — 2026-07-11 (evening)

Session summary for the next pickup. M5 (Discovery) and M6 chunk (a) are DONE and
committed; next up is M6 chunk (b), reader auto-scroll. Both suites green after every
commit; core suite 30 tests + app suite 69 tests, all genuinely executing.

## What shipped (all on master)

- `280621b` — M5(a): engine discover sections via the pull API. Domain `HomeSection`;
  `MangaSource.getHomeSections` (default empty) + `CatalogRepository.homeSections`
  (abstract — deliberate; a repo default would mask a forgotten override);
  `PaperbackExtension` duck-types `getDiscoverSections`, passes the ORIGINAL stub Value
  back to `getDiscoverSectionItems`, first page only, null metadata. Trust-boundary
  hardening from review: stub loop capped at 32, non-object stubs / non-integral types /
  non-object results all become named `ExtensionDataException`s, genres-skip (type 4)
  runs BEFORE dedupe. HTTP fixtures recorded live for FlameComics/MangaBat/Toonily;
  WebtoonXYZ stays fixture-less (Cloudflare 403s even the recorder — same as search;
  challenge coverage lives in `LiveDetectWebtoonXyzTest`).
- `d977e06` — M5(b): Browse renders per-source discover sections. Source chips are the
  tabs; sections load lazily per selected tab into a session cache (errors retried on
  revisit, never prefetched — polite-scraper); titled cover shelves; search-on-submit
  overlays a per-source-tagged results grid (fixes old stale-results-across-chips); blank
  submit returns to sections. Review fixes: search job cancellation, challenge state split
  per mode + click-time solve dispatch, query captured at submit, pending-guard race
  removed (fetch in screen scope, composition-captured effect key), honest empty/error
  states for the source list.
- `a4de7a6` — M6(a): search-everywhere palette. Double-Shift from anywhere (Window-level
  `onPreviewKeyEvent` in Main.kt → `DoubleShiftDetector`, which requires a Shift RELEASE
  between taps so held-Shift OS auto-repeat can never trigger). Text-only rows — owner
  requirement, no images/Coil in the palette. All colors from the theme — owner
  requirement; scrim uses `colorScheme.scrim`, zero color literals in Palette.kt.
  Extensibility contract (the design center): `PaletteHit` (action = plain lambda) /
  `PaletteProvider` (`fun interface`) / `PaletteTab`, one registration point
  `paletteTabs()` — a new searchable feature is one provider + one line; a new mode
  (backlog online tab) is a new tab. Candidates fetched once per open/tab-switch; every
  keystroke ranks cached candidates in memory via central `fuzzyScore` (multi-start greedy
  anchoring so "lev" ranks "Solo Leveling" over "Television"). v1 tabs: All / Manhwa /
  Actions (screens + themes). Review fixes: overlay-level key handling (survives chip
  clicks), Reader focus restore on palette close, stale-hit flash cleared, selection
  scrolls into view (bottom-edge pinning), honest Down/Enter + repeated-char tests.

## Process correction (owner-flagged, applies to every future chunk)

All three chunk reviews this session silently ran on the SESSION model, not Opus: the
"pin to Opus" text passed in the code-review workflow args is prose to the finder agents,
not a model parameter. CLAUDE.md says reviews are pinned to Opus — that is a decision
(cost + reviewer-independence from the arbitrating session model), not an intent.
**From the next chunk boundary on: copy/edit the code-review workflow script setting
`model: 'opus'` on the finder/verify `agent()` calls (or use direct Agent dispatches with
model opus), then VERIFY in the run metadata that agents actually ran on Opus.** Recorded
in auto-memory (`code-review-must-run-on-opus`). The shipped reviews were still real
(30 confirmed defects found and fixed across the three chunks) — no re-review needed.

## Decisions taken (owner-visible)

- Palette is text-only (no cover images) and fully theme-driven — both explicit owner
  requirements; treat as invariants for any palette follow-up work.
- `CatalogRepository.homeSections` stays abstract (no default) — rejected a review
  suggestion; production safety over test-fake convenience.
- One solve at a time is still screen-local; the app-wide gate belongs in
  ChallengeSolver/AppShell (recorded ceiling, deferred to refinement).
- CLAUDE.md corrected: engine is GraalJS (was QuickJS), binding-reachability invariant
  restated. NOTE: CLAUDE.md is in .gitignore, so this fix is local-only and its header's
  "checked into the codebase" claim is stale — owner was told; decide whether to track it.

## Ceilings recorded in PLANNING (for the refinement phase)

- fuzzyScore matches later query chars greedily; full-DP alignment is the escape hatch if
  ranking complaints appear.
- Online palette tab will need a per-tab fetch policy (live query, per-keystroke refetch).
- Palette-opened Details goes back to Library (nav-origin refactor still pending; same for
  Search-opened Details since M4).
- Browse: blank-query submit is the only way back from results to sections (no clear
  button); a solve is cancelled if the user leaves the screen mid-solve.
- FlameComics homepage fixture is shared across LiveRecord suites — on buildId rotation
  re-record ALL FlameComics live tests together (documented in LiveRecordDiscoverTest).

## Next steps

1. M6(b): reader auto-scroll — hotkey-bound toggle, speed configurable in Settings
   (PLANNING §9 M6). Small chunk: ReaderScreen + Settings + tests. Palette gives the
   precedent for hotkey handling; keep it inside ReaderScreen's existing key handler.
2. Chunk review pinned to Opus per the process correction above.
3. After M6: UI/functionality refinement phase (owner call on scope) — the ceilings list
   above is the natural backlog for it.

## The working loop (unchanged, for a fresh session)

Fable/session model = decision maker (briefs, arbitration, commits); implementation goes
to Sonnet subagents with verified-delegation briefs (locked decisions, STOP rule, pasted
evidence); review at every chunk boundary via the code-review workflow — ON OPUS (see
above); independent verification (forced `--rerun-tasks`, JUnit XML check, screenshot
visual review) before every commit.
