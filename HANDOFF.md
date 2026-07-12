# Handoff — 2026-07-11 (late night)

Session summary for the next pickup. R9 shipped: a project-wide quality/readability/
performance review (owner-requested neckbeard pass) followed by fixing all nine findings.
Both suites green after the commit: core 109 + app 103, forced rerun, JUnit XML verified.

## R9 shipped (58d41c5)

Review verdict first: the codebase was healthy — no architectural problems, boundaries
hold, the recorded ceilings were left alone. Nine targeted findings, all fixed:

- **Details session cache** (`DetailsCache` in DetailsScreen.kt, hoisted in AppShell):
  Reader→Details back-nav no longer refetches details+chapters (was: spinner + two fresh
  JS contexts + politeness delay on every chapter exit). Freshness semantics unchanged —
  every fresh open (Library/Search/Browse lambdas AND the palette's navigate, per an Opus
  review finding) invalidates first; only Reader-back reuses. finished/latestProgress
  always re-read live.
- **Reader page prefetch** (`pagesToPrefetch` + effect in ReaderScreen.kt): next 5 network
  pages enqueue into Coil before they scroll into view; offline segments and custom
  pageContent (tests/harness) never prefetch. Pure helper unit-tested (ReaderPrefetchTest).
- **Shared-engine bundle cache** (ExtensionRuntime/PaperbackExtension): one polyglot
  Engine + cached Source per runtime, one runtime per extension instance; contexts stay
  fresh per call. MEASURED first (owner threshold ~100ms): 300KB Toonily bundle eval was
  81–174ms per call, now 23–35ms (~4x). Opus sandbox review: 0 blockers — shared Engine
  shares compiled code only, never guest state (verified against GraalVM docs); Engine is
  thread-safe for concurrent contexts; SandboxTest now pins denials on BOTH context paths
  (standalone and shared-engine, closing the review's coverage-gap finding).
- **Streaming bundle cap** (InkdexRepo): prepareGet+execute (implementer discovery: Ktor
  3.5's default SaveBody plugin eagerly buffers the WHOLE body inside http.get before
  caller code runs — execute{} is what actually keeps it a stream), Content-Length
  pre-reject, capped readRemaining(MAX+1). Chunked encoding is caught by the capped read.
- **Explicit HTTP timeouts** (AppGraph): HttpTimeout connect 10s / request 120s / socket
  30s, CIO engine requestTimeout disabled (its implicit 15s default silently governed —
  and could abort — large image downloads). scheduleRequest keeps its own 30s cap.
- **Extensions action-error containment**: a failed install/remove now shows a banner
  above the still-visible list instead of blanking the screen (was: action failures wrote
  the load-error state). Test proves the list survives.
- **Shared challenge-solve UI** (new ChallengeUi.kt): ChallengeErrorContent +
  SolveProgressHint, replacing near-identical blocks in Reader/Details/Browse and the
  duplicated hint in Search. Url-capture semantics preserved (review-verified).
- **Cookie purge**: deleteExpiredCookies (<= now, matching the read filter) at AppGraph
  init; expired rows no longer accumulate forever.
- **Details sort memoization**: both chapter sorts behind remember(chapters).

Reviews: two guided single Opus dispatches (app diff; core/engine diff — mandatory,
sandbox-adjacent). App review: 0 blockers, 1 SHOULD-FIX (palette navigate skipped cache
invalidation — fixed at the shared lambda). Core review: 0 blockers, sandbox invariant
confirmed; accepted NITs fixed (SandboxTest engine-path probe, expiry <=, bundleSource
rename); rejected as fine: unclosed Engine (GC-sound on JVM, documented in-code),
cached(true) (explicit-of-default).

Execution followed the loop: 3 parallel disjoint-file implementers (chunk 1), then two
sequenced single dispatches (chunks 2–3), engine change inline by the decision maker
after measurement. All briefs enforced timeless comments.

## Ledger updates

- PLANNING §10: reader page images share the downloads host-policy-bypass ceiling family
  (Coil sends no jar cookies / pinned UA → CF-walled pages can 403 in the reader even
  after a solve). Route image fetches through host policy when that layer lands.
- Details cache is session-lifetime; palette/list opens always refetch, so "new chapters"
  behavior is unchanged. Reader-back is the only cached path.
- Prefetch dedup key is page.url (unique per resource; re-enqueue after re-anchor is a
  harmless cache hit).

## Next steps

1. Owner should feel out the reader: back-nav from a chapter should be instant now,
   binge-scroll and auto-scroll should hit far fewer loading boxes.
2. Refinement backlog unchanged (PLANNING §12 + recorded ceilings): nav-origin back-stack,
   fuzzyScore full-DP, purge-source-data, library unread badges, per-manhwa Continue
   palette hit.

## The working loop (unchanged, for a fresh session)

Fable/session model = decision maker (briefs, arbitration, commits); implementation goes
to Sonnet/Haiku subagents with verified-delegation briefs (locked decisions, STOP rule,
pasted evidence); parallel dispatches only on disjoint file sets; review at every chunk
boundary = ONE guided Opus Agent dispatch (never the review workflow); independent
verification (forced --rerun-tasks, JUnit XML check) before every commit.
