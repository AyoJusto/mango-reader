# C1 — Persistent details cache (stale-while-revalidate)

Goal: Details opens instantly for any manga seen before, and the sidebar Continue action
reaches the reader without a visible Details load. One root fix: persist details + chapters,
render the stored copy immediately, revalidate live in the background.

## Locked decisions

- Storage is normalized SQLDelight tables (repo style), not JSON blobs:
  - `details_cache(source_id, manga_id, title, cover, description, status, authors, tags,
    updated_at, PK(source_id, manga_id))` — authors/tags as delimited text columns is
    acceptable if a join table is overkill; implementer's call, noted in return.
  - `chapter_cache(source_id, manga_id, chapter_id, number, title, published_at, position,
    PK(source_id, manga_id, chapter_id))`.
  - Migration `5.sqm`; the existing MigrationTest asserts migrated == fresh automatically.
- `CatalogCache` interface lives in core domain (no infrastructure types):
  `get(sourceId, mangaId): CachedManga?` (details + chapters + updatedAt),
  `put(sourceId, mangaId, details, chapters)`. No TTL, no clear-on-uninstall (cached copy
  stays readable after uninstall, consistent with downloads policy; revalidation simply
  fails quietly).
- `put` replaces the chapter set atomically (transaction: delete manga's rows, insert new) —
  a shrunk chapter list must not leave orphans.
- SqlCatalogCache implementation in core data, wired in AppGraph.
- Policy in the Details loader (app):
  - Cached copy exists → render it immediately (no skeleton), then always fire a background
    live fetch; on success `put` + update the UI in place; on failure keep the stale copy
    silently (error/challenge UI only when there is nothing cached). Consequence: a cached
    manga never surfaces a challenge from Details, from any entry point — the Reader's Solve
    button (live page fetches still throw) is the escape hatch that refreshes clearance, and
    new chapters on a walled source stay invisible until a solve happens there.
  - No cached copy → today's behavior exactly (skeleton → live fetch → content or error).
- The in-memory `DetailsCache` and every `invalidate` call site (AppShell onContinue,
  palette navigate) are deleted — the persistent cache with revalidation subsumes the
  freshness policy that invalidation existed to protect.
- `autoContinue` logic is unchanged: it fires when the chapter list is available — which,
  with a warm cache, is the first frame. No special-casing the Continue path beyond that.
- Reader `pages()` stays live; out of scope.

## Waves

1. Core: schema tables + 5.sqm + `CatalogCache` domain interface + `SqlCatalogCache` +
   repository tests (round-trip incl. published_at instants, atomic chapter replacement,
   get on missing manga). `:core:jvmTest` green.
2. App: AppGraph wiring, DetailsScreen loader rewritten to stale-while-revalidate,
   AppShell/palette invalidate removal, in-memory DetailsCache deleted. Flow tests: cached
   content renders without the catalog completing (fake catalog that suspends), background
   refresh updates content in place, refresh failure keeps stale content, autoContinue with
   warm cache fires onOpenChapter without a live fetch completing. `:app:jvmTest` green.
3. Opus review at the boundary (loader flow + policy; schema plumbing itself is exempt per
   project rules but rides along), screenshot check (Details visuals unchanged), commit.

## Risks to watch

- Double-render race: cached render + refresh completing quickly must not flicker; the
  refresh updates state only when content actually differs, or unconditionally but with
  stable keys (chapterId) so the LazyColumn doesn't jump. Implementer verifies scroll
  position survives a revalidation that changes nothing.
- The challenge (Cloudflare) path: with stale content on screen, a challenge on refresh
  must not replace content with the challenge card; it should be ignored like any other
  refresh failure (the user can force it by opening from Browse/Search, which still surfaces
  challenges when nothing is cached).
