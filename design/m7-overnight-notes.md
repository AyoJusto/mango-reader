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

- (populated as the run progresses)

## Deferred / ceilings recorded

- Search-history keyboard highlight navigation (design: "Enter on a highlighted row also
  replays") — click-only in v1.
- Search stays search-on-submit; the design's "live results at first keystroke" assumes a
  local index and would hammer sources cross-source (polite-scraper rule). History list
  swaps out on first keystroke as designed.
- Collection filter selection is session-only (not persisted across restarts).

## Review arbitration log

- (populated per chunk)

## Suites / commits

- (populated per chunk)
