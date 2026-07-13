# Reader prefetch depth

Date: 2026-07-13
Status: approved by owner (this session), pending spec review

## Problem

Fast scrolling can outrun the reader's page prefetch, causing visible loading
stutter mid-chapter and at chapter boundaries. The original "auto download next
chapter" idea was probed and scoped down: continuous-reading smoothness is the
only gap the owner wants closed. Cross-session warming and true offline
buffering are explicitly out of scope.

## Decision

Deepen the existing Coil-based prefetch in
`app/src/jvmMain/kotlin/dev/mango/app/ReaderScreen.kt`. Two constant changes,
no new code paths, no UI, no settings entry:

- `PREFETCH_PAGE_COUNT`: 5 → 20. The prefetch effect (same headers and decode
  caps as real page loads, URL-deduped, default renderer only) stays as is.
- `AUTO_LOAD_THRESHOLD`: 4 → 20. Matches the window so the next chapter's page
  list is fetched ~20 rows out and its pages flow into the prefetch window;
  without this the deeper window starves at chapter boundaries.

Storage is bounded by Coil's LRU disk cache, which evicts old chapters
automatically. No eviction code is needed.

## Invariant (owner condition)

Prefetching and auto-appending must never advance read progress. Progress
writes stay scroll-driven only: the debounced page writer keys off
`listState.firstVisibleItemIndex` and the chapter-finished writer requires the
chapter's last page row to have scrolled into view (ReaderScreen.kt:339-400).
Neither the append path nor the prefetch path calls `library.setProgress`.
Verified against current code; re-verify at review.

## Accepted trade-off

Reading within ~20 rows of a chapter's end always fetches the next chapter's
page list and warms its first pages, even if the user stops there. That is the
feature.

## Testing

- Existing `pagesToPrefetch` behavior tests take the count as a parameter and
  keep covering the helper.
- Any reader test that encodes the old constant values (threshold 4 / window 5)
  is updated to the new values, not weakened.
- No new test surface: the change is two tuning constants on already-tested
  machinery.

## Out of scope (probed and deliberately dropped)

- Auto-download via `DownloadManager` with provenance-flagged eviction.
- Cross-session next-chapter warming.
- A user-facing toggle or prefetch-depth setting (no palette entry needed since
  nothing user-facing changes).
