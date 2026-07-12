# Handoff — 2026-07-12

Session summary for the next pickup. The MU UI-overhaul milestone kicked off: owner built
a full visual-direction lookbook in claude.ai/design, it was pulled into the repo, planned
in depth, the U2 chrome spike ran on the owner's machine, and U1 (theme foundation)
shipped. Both suites green after the commit: core 109 + app 110, forced rerun, JUnit XML
verified.

## MU milestone standing docs

- `design/lookbook-handoff.md` — the design spec of record (tokens, type, motion,
  per-screen specs). Every MU brief and review cites it by section (owner instruction).
- `design/ui-milestone-plan.md` — chunks U1–U6 with dispatch maps, acceptance criteria,
  and the U2 spike findings (locked decisions).
- PLANNING §9 MU entry, §13 loop addendum (implementers never spawn subagents; single
  Opus implementer allowed only where a spike shows platform-subtle work; Opus reviews
  every boundary).

## U1 shipped (a9a187d)

Token theme system per the lookbook: `MangoTheme` data class + `LocalMangoTheme` /
`ProvideMangoTheme` (Material interop with `surfaceTint` pinned to bg1 — tonal elevation
must never tint toward the accent; that bug was caught by screenshot eyeball, not code
review). ThemeJson validated import/export (trust boundary: never throws, failed import
leaves theme untouched) + ThemeStore at `<dataDir>/theme.json`. Appearance settings
(accent presets, export/import) registered in SETTINGS_ENTRIES with palette hits.
kanagawa-dragon/midnight registry retired; Mango Dark is the only built-in — custom
looks come from accent override + JSON import. All screens read tokens; zero
`colorScheme` color reads remain.

Execution: 1 Sonnet (theme core, ~14 min, honest 4-deviation report all review-verified),
then 3 parallel recolor agents (2 Haiku + 1 Sonnet, grep self-checks instead of Gradle —
concurrent agents must not share the daemon). Guided Opus review: SHIP, 0 blockers,
5 NITs (3 fixed, 1 dissolved, 1 deferred to U6 and recorded in the plan doc).

## U2 chrome spike — findings locked (in ui-milestone-plan.md)

JBR `WindowDecorations.CustomTitleBar` is the chrome mechanism (IntelliJ's): native drag,
double-click, Win+arrows, snap layouts, edge resize, OS-drawn buttons — all verified on
the owner's machine, running on the IDEA-bundled JBR 25 via a temporary
`compose.desktop.application.javaHome` switch. Compose needs the forceHitTest handshake
(pattern snippet preserved in the plan doc). Blur measured free; owner decision: sidebar
alpha-only, blur reserved for the palette. Fallback on stock JDK: OS-decorated window,
our bar below it (this is what CI/tests exercise).

## Next steps

1. U2 dispatch (chrome + sidebar): Sonnet per the spike verdict. Open decision for the
   brief: how dev-runs get a JBR (owner's IDEA jbr path is machine-local; a pinned
   jbrsdk download is the clean answer, also needed later for packaging).
2. Then U3 component kit → U4 Library → U5 Reader → U6 remaining screens + palette
   (U6 carries the deferred swatch cosmetics + may run as a hands-off batch experiment,
   owner-approved idea).
3. Owner should run the app and feel the new theme: accent swatches apply live,
   export → edit JSON → import round-trips.

## The working loop (unchanged, for a fresh session)

Fable/session model = decision maker (briefs, arbitration, commits); implementation goes
to Sonnet/Haiku subagents with verified-delegation briefs (locked decisions, STOP rule,
pasted evidence); parallel dispatches only on disjoint file sets; review at every chunk
boundary = ONE guided Opus Agent dispatch (never the review workflow); independent
verification (forced --rerun-tasks, JUnit XML check) before every commit. MU addendum:
concurrent implementers never run Gradle — grep/compile self-checks in briefs, the
decision maker runs the suites once after all groups land.
