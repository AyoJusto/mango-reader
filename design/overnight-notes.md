# Overnight run — 2026-07-12/13 (for morning triage)

Owner directive: work autonomously through U3–U6 per `design/ui-milestone-plan.md`,
full loop per chunk, best judgment on calls, document everything here. Nothing below
is a blocker; it is the morning's triage list.

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

## Known open questions carried in

- Sidebar continue cards show "p. N"; chapter numbers land with U4's chapter plumbing
  if the bulk lookup is cheap, otherwise stays deferred (will note outcome below).
- U5: title bar stays visible over the reader (merged-chrome constraint) — I will pick
  the treatment per the design's immersion intent and note it here.

## Issues found during the run

- Infra: implementer agents were killed twice by a transient API content-filter error,
  both times mid-emission of a large Kotlin file (U2 once — resume worked; U3 twice —
  fresh dispatch with "write the file incrementally" instructions worked around it).
  No code impact; noting in case it recurs and slows a chunk.
