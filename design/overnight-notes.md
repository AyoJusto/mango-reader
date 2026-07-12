# Overnight run — 2026-07-12/13 (for morning triage)

Owner directive: work autonomously through U3–U6 per `design/ui-milestone-plan.md`,
full loop per chunk, best judgment on calls, document everything here. Nothing below
is a blocker; it is the morning's triage list.

## RESULT: all four chunks shipped — the MU visual pass is complete

- U3 component kit — 7a85ad5
- U4 Library (grid/list, unread badges, chapter numbers) — ccaf293
- U5 Reader (immersive overlay, cursor hide, skeletons) — 92790ee
- U6 all screens + Spotlight palette + new settings — 7df9abb
- Every chunk: Opus-reviewed SHIP, findings arbitrated + fixed, both suites
  forced-rerun green (final: core 116, app 134), boards eyeballed by me.

## Accepted review NITs (recorded, no action unless they bother you)

- Sidebar + palette open together: the frosted panel samples already-blurred content
  (double blur) — sits behind the palette scrim, barely perceptible.
- "Mark finished" resets saved page positions to 0 for partially-read chapters (they
  are finished afterward, so Continue skips them — semantically fine).

## Needs your eyes (couldn't be verified without you)

- U2 live-run items you already verified (frosted sidebar, click-block, hover fade) are
  committed in f49bfd0. Everything committed after that has had screenshot-board checks
  by me but NO live JBR run — worth one app session over coffee.

## Judgment calls made overnight (flag if you disagree)

- U3: filled buttons (PRIMARY/SECONDARY/DANGER) have no hover fill change — the design
  defines no filled-hover color and I didn't invent a token; press-scale gives the
  feedback. Reviewer ruled it defensible. If you want hover on filled buttons, it's a
  one-token decision in the morning.
- U3: SearchScreen and ReaderScreen still have inline challenge rows in danger red
  (mixed error/challenge renderers). Deferred to U6/U5 which restyle those files —
  carried explicitly in their briefs, not forgotten.
- U4: series "Completed" treatment = all chapters STARTED (not all finished). Reviewer
  ruled the drift bounded and self-healing; the exact upgrade (finished_count in the
  same SQL join) is recorded if premature Completed pills ever bother you.
- U5 RULING (title bar over reader): the merged bar STAYS visible while reading. The
  native window controls live in it — hiding it would remove drag/min/max from a
  windowed app. Board 05's "zero chrome" applies to the content area below the bar;
  F (fullscreen) remains the total-immersion mode. Revisit in the morning if you want
  bar auto-hide in fullscreen only.
- U5: cursor-hide with the overlay ships as always-on behavior (board 05); the board 09
  "Hide cursor" toggle registers as a setting with U6's settings restyle.

## Known open questions carried in

- Sidebar continue cards show "p. N"; chapter numbers land with U4's chapter plumbing
  if the bulk lookup is cheap, otherwise stays deferred (will note outcome below).
- U5: title bar stays visible over the reader (merged-chrome constraint) — I will pick
  the treatment per the design's immersion intent and note it here.

- U6 scope trims (restyle ships without these; each needs data/APIs that don't exist yet):
  Downloads "Pause all" (no pause API on DownloadManager) and bulk "Clear done";
  Search "In library" pill (needs LibraryRepository threaded into SearchScreen);
  Settings "Download location" row (real storage-config infra);
  Browse Popular/Latest/Filter listing modes (source-listing machinery — restyle keeps
  current behavior). All are morning-triage candidates, none blocks the visual pass.
- U6: Details "Mark finished" implemented as an app-level loop finishing every loaded
  chapter via the existing setProgress (ponytail: N upserts; a bulk core op if it ever
  feels slow).
- U6 discovery: board 07's Browse infinite-scroll footer assumes PAGINATION that mango
  doesn't have — CatalogRepository.search takes a page param but no screen/state tracks
  pages or fetches page 2+. That's a feature ticket (paging state machinery), not a
  restyle; the footer ships when paging does.
- U6: Details in-progress chapter rows show "reading · p. N" not "· X%" — page counts
  per chapter aren't loaded on Details; percentage needs page-count data.
- Cosmetic follow-up: the Strip-width/Auto-scroll sliders still use Material's default
  thumb/track rendering (looks clunky against the new rows); board 09 wants a 4dp track
  with a 14dp round thumb — a small custom slider or Material colors pass.

## Issues found during the run

- Infra: implementer agents were killed twice by a transient API content-filter error,
  both times mid-emission of a large Kotlin file (U2 once — resume worked; U3 twice —
  fresh dispatch with "write the file incrementally" instructions worked around it).
  No code impact; noting in case it recurs and slows a chunk.
