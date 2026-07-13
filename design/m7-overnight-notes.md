# M7 overnight run — morning triage notes (2026-07-12/13)

Owner-directed autonomous run: M7 (update checks, search history, collections) per the
approved plan. This file collects every judgment call, scope trim, and review arbitration
made without you. Triage top to bottom.

Plan of record: `~/.claude/plans/i-want-to-understand-tender-chipmunk.md` (chunks V1→V2, S1,
C1→C2). Spec: `design/lookbook-handoff.md` addendum (written tonight from the updated
lookbook HTML — the online README copy is stale; consider having web Claude regenerate it).

## Decisions locked before the run (you approved via plan questions)

- Drag-a-cover-onto-a-collection-tab: deferred (picker covers filing). Ctrl+1–9 tab jumps
  and chip-row overflow scrolling deferred with it.
- Order: V1→V2, S1, C1→C2.

## Judgment calls made during the run

- V1: first cache fill stamps `first_seen_at = 0` (never "new") — including the edge where a
  manga cached with an empty chapter list later gains chapters. Prevents a fresh library-add
  flooding "+200 new"; the tradeoff is that the first populated fill is the baseline.
- V1: a series added via download (never opened in Details) has `last_opened_at = 0`, so every
  chapter discovered by later checks counts as new until the first Details open. Deliberate.
- V1: AppGraphTest's v1-rewind fixture needed one mechanical line (DROP COLUMN
  last_opened_at) so the migrate-from-v1 test still reproduces the true v1 shape. Assertions
  untouched; implementer flagged it per STOP rule, I accepted.

## Deferred / ceilings recorded

- Search-history keyboard highlight navigation (design: "Enter on a highlighted row also
  replays") — click-only in v1.
- Search stays search-on-submit; the design's "live results at first keystroke" assumes a
  local index and would hammer sources cross-source (polite-scraper rule). History list
  swaps out on first keystroke as designed.
- Collection filter selection is session-only (not persisted across restarts).

## Review arbitration log

- V2 (Opus, guided): SHIP conditional on one SHOULD-FIX, applied — the single-flight
  `checking` flag was set inside the launched coroutine, so two rapid clicks could both pass
  the guard on the event-loop; the flag now flips synchronously before launch. NIT accepted
  as recorded gap: the pill-pulse "no pulse on first composition" guard is untested
  (Animatable assertions in Compose are awkward; revisit if the pulse ever misbehaves).
- V2 dispatch note: the first implementer dispatch died to a transient API content-filter
  kill mid-emission (same failure mode as the U3–U6 night, 4 occurrences there); the fresh
  dispatch reconciled the partial working tree and completed. No action needed.

- V1 (Opus, guided): SHIP, zero blockers/should-fixes. One NIT accepted as a recorded gap:
  LibraryUpdater's within-source sequential ordering is structurally guaranteed but untested
  (a latch harness would pin it; disproportionate for now — revisit if the updater's fan-out
  is ever refactored).

## Suites / commits

- V1: core 133 + app 147, forced rerun, JUnit XML verified 0 failures. Commits `0f2589a`
  (docs), `bf13720` (V1).
- V2: core 133 + app 161, forced rerun, JUnit XML verified 0 failures. Commit `9dbe118`.
- C1: core 143 + app 174, forced rerun, XML verified 0 failures. Commit `ae66a21`.
  Review: SHIP, 2 NITs accepted — the app-test fake pre-seeds the default "Reading" shelf
  (real repo self-heals it on first add; a truly virgin DB shows no shelves until then —
  conscious call), and fake/real diverge on a contract-violating partial reorder list.
  Deviations accepted: COALESCE('') for the dialect's non-null GROUP_CONCAT mapper; the
  v6→v7 migration test gained a collection_member stand-in (existing precedent); stub
  overrides in LibraryUpdaterTest's private fake.
- C2: core 143 + app 182, forced rerun, XML verified 0 failures. Commit `5796a88`.
  Run resumed the morning after a power cut (C1 was fully landed; C2 had not started).
  Dispatch note: three consecutive implementer dispatches died to the API content-filter
  kill (same failure mode as V2/U3–U6, but not converging on retry); re-briefed as three
  narrow slices (chip row + wiring, manage dialog, tests) and all completed cleanly.
  Review: SHIP, 2 SHOULD-FIXes applied — the ▾ picker on a not-in-library series orphaned
  collection_member rows (now toggle-implies-add: addToLibrary first, then setMembership
  with exactly the checked set; danger row hidden when not in library), and rapid picker
  toggles computed from a stale observed set (picker now keeps local checkbox state for
  the popup's lifetime). NIT applied: unused theme local. NIT rejected as recorded rough
  edge: generic EmptyState copy when a non-empty library's selected shelf is empty.
- S1: app 174, forced rerun, XML verified 0 failures (core untouched). Commit `ca4a4c0`.
  Review: SHIP, 3 NITs — applied the hover-gate on the invisible remove ✕ (a click on a row's
  right edge must replay, not silently remove); accepted "clearing the query hides live
  results behind the history list" as designed; accepted a slightly-overnamed test.
