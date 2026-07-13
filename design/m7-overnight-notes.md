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

- V1 (Opus, guided): SHIP, zero blockers/should-fixes. One NIT accepted as a recorded gap:
  LibraryUpdater's within-source sequential ordering is structurally guaranteed but untested
  (a latch harness would pin it; disproportionate for now — revisit if the updater's fan-out
  is ever refactored).

## Suites / commits

- V1: core 133 + app 147, forced rerun, JUnit XML verified 0 failures. Commits `0f2589a`
  (docs), `bf13720` (V1).
