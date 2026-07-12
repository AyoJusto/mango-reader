# Handoff — 2026-07-11 (night)

Session summary for the next pickup. Both owner-reported reader bugs are fixed, M6(b) is
DONE (M6 complete), the palette-completeness invariant landed, and refinement chunk R4
(app-wide solve gate + reader polish) shipped. Both suites green after every commit;
core 101 tests + app 87 tests, all verified via forced rerun + JUnit XML.

## R4 shipped (1329972) + next up

- `1329972` — SingleFlightChallengeSolver (Mutex.tryLock decorator around
  JcefChallengeSolver in AppGraph: concurrent solve() returns false, gate releases under
  cancellation/exceptions); reader listState keyed on the anchor (P's one-frame stale-
  offset jump gone); auto-scroll pauses under the open palette and auto-resumes.
  Implemented as TWO PARALLEL disjoint-file dispatches (Haiku: gate; Sonnet: reader) —
  owner corrected the loop: implementation may fan out across Sonnet/Haiku agents when
  file sets are disjoint (auto-memory `parallel-cheap-implementers`; CLAUDE.md updated).
  Opus review found one real SHOULD-FIX (overlay Prev button re-anchored without stopping
  auto-scroll → drive loop stranded on the detached listState); fixed at altitude — the
  nav helpers stop auto-scroll themselves — with a regression test. fuzzyScore full-DP
  DEFERRED (owner call; ceiling stays).
- `f0deed4` — R5 SHIPPED: Details shows download state (disabled ✓ on downloaded rows,
  bulk actions skip what's on disk, all derived live from observeDownloads()) + owner
  addition: "Clear storage" button on Details (visible only with downloads, one confirm
  dialog) backed by DownloadManager.clearDownloads — rows deleted first, then a
  best-effort deepest-first file sweep through the same safeSegment mapping as the write
  path (Opus review confirmed the delete can't escape the downloads root; symlinks are
  removed as links, not followed). Ceilings: clear-during-active-download orphans that
  one chapter's files (next clear removes them); DONE rows with hand-deleted files read
  as downloaded until cleared.
- **Triaged, awaiting owner go: R6 — extension removal.** No uninstall exists anywhere
  (plain omission, never specced). Agreed design: CatalogRepository.uninstall(sourceId)
  (delete source row + bundle file + evict cached engine — eviction machinery exists),
  "Remove" button on installed Extensions rows, keep library/downloads/progress data
  (downloads stay readable offline; live loads fail honestly until reinstall; cascade
  delete recorded as ceiling, not built).

## New owner invariant (2026-07-11): search-everywhere completeness

Owner asked why the new auto-scroll setting wasn't in the palette and set the rule: ALL
user-facing actions and settings must be reachable from double-Shift. Now enforced three
ways (recorded in CLAUDE.md invariants): (1) `SETTINGS_ENTRIES` in SettingsScreen.kt is
the single settings registry — a `settingsProvider` in Palette.kt derives "Setting: X →
open Settings" hits from it; (2) completeness tests in PaletteFlowTest/SettingsScreenTest
iterate the registry, so an unregistered setting fails the build; (3) one-off actions
(which have no registry) are covered by the CLAUDE.md invariant in every brief/review:
"a feature that can't be found from double-Shift is not done." Palette hits navigate to
Settings — inline value editing in the palette was deliberately NOT built (text-only
palette invariant); owner was offered it as a future chunk.

## What shipped (all on master)

- `36d3a74` — Reader infinite scroll (owner-reported P1: no way to reach the next
  chapter). Owner's call: true infinite scroll, not chapter navigation. `Screen.Reader`
  carries the sorted chapter list from Details; chapters load as segments (downloads-first
  per segment, divider rows between chapters, end-of-series footer); the next chapter
  auto-appends near the strip end (single in-flight load gated by a NextLoadState machine,
  inline Retry/Solve row on append failure; the initial chapter keeps the full-screen
  error/challenge flow); progress writes are per-current-segment with divider/tail rows
  attributed to the PRECEDING chapter (a chapter is never marked read by its divider);
  N/Next jump forward (loading if needed), P/Prev re-anchor like a fresh open.
- `7e4ee09` — Controls overlay threshold (owner-reported bug: appeared on any mouse move;
  now only on hover within 80dp of the top edge — CONTROLS_REVEAL_BAND — or click) +
  M6(b) auto-scroll: A toggles a frame-clock drive loop (dt × speed, suspend scrollBy);
  speed = `Settings.autoScrollSpeed` (Float dp/s, default 120), Settings-screen slider
  30–600 persisted only on drag-finish, hoisted Main → AppShell exactly like the theme.
  Manual paging/N/P/Escape stop it. At the hard end of loaded content it WAITS while the
  next chapter is loading (resumes on append — infinite reading) and stops for real only
  at the last chapter or a failed append.

## Process correction #2 (owner-flagged, supersedes part of the previous one)

Owner killed the multi-agent code-review Workflow mid-run: **"You are using too many
subagents for reviews on tiny changes. Guide them instead of using the built in token
waster."** New standing rule (recorded in auto-memory `guided-single-agent-reviews`,
cross-linked from `code-review-must-run-on-opus`): chunk reviews are ONE direct Agent
dispatch with `model: opus`, briefed by the decision maker with the diff command, the
CLAUDE.md invariants, and the specific hotspots it flagged while reading the diff itself.
The Opus pin stays (that part of the earlier correction holds); the workflow fan-out is
retired unless the owner explicitly approves one. Both R1 and R2 reviews this session ran
this way: 0 blockers each, and the findings were real (R1: nav-handler dedup + a test
assertion tightened; R2: the wait-while-loading stop condition + named band constant).

## Decisions taken (owner-visible)

- Infinite scroll is the reading model (owner picked it over plain next-chapter nav);
  N/P and Prev/Next buttons ride on top of it.
- Auto-scroll setting is the SPEED; the runtime toggle is the A key (not a Settings
  switch). Slider, not presets (owner picked).
- Auto-scroll does not stop at an in-flight chapter boundary (arbitrated from the R2
  review's F1): it idles at the loaded end and resumes when the append lands.

## Ceilings recorded in PLANNING (refinement-phase backlog)

- No upward prepend: P re-anchors instead (LazyColumn prepend scroll-anchoring is the
  escape hatch; also a possible one-frame jump on P since listState isn't keyed on the
  anchor).
- The progress snapshotFlow rebuilds the flattened row list every scroll frame (O(loaded
  pages)); emit the raw index and compute post-debounce if huge strips appear.
- Auto-scroll keeps running (hidden) while the palette overlay is open (reader loses
  focus so A can't reach it); pause-on-palette if it ever matters.
- Cursor parked in the top band re-reveals controls on each auto-hide (emergent from
  synthetic hover moves; reads as intended hover-to-pin).
- Plus the pre-existing ones: fuzzyScore greedy matching, palette online tab fetch
  policy, palette/Search Details back-nav origin, Browse blank-submit-only return,
  FlameComics fixture rotation.

## Next steps

1. M6 is DONE. Next: UI/functionality refinement phase (owner call on scope) — the
   ceilings list above is the natural backlog.
2. Owner should run the app and feel out the new reader: scroll across a chapter
   boundary, try A at different slider speeds, check the top-edge controls reveal.

## The working loop (updated, for a fresh session)

Fable/session model = decision maker (briefs, arbitration, commits); implementation goes
to Sonnet subagents with verified-delegation briefs (locked decisions, STOP rule, pasted
evidence); review at every chunk boundary = ONE guided Opus Agent dispatch (see process
correction #2 — never the review workflow); independent verification (forced
`--rerun-tasks`, JUnit XML check) before every commit.
