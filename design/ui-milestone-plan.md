# MU — UI overhaul milestone plan

Execution plan for implementing the visual direction in `design/lookbook-handoff.md`
(the spec of record; exact tokens, motion values, and per-screen specs live there, not
here). Six chunks, U1–U6, each independently shippable, each ending in a guided Opus
review and both suites green (forced rerun, JUnit XML verified) before commit.

## Agent policy for this milestone (owner-agreed 2026-07-12)

- Fable (session model): architecture, spikes, briefs, arbitration of review findings,
  commits. No bulk implementation.
- Implementers: Sonnet by default; Haiku for purely mechanical recolor sweeps. One
  chunk exception: U2's window-chrome piece may go to a single Opus implementer if the
  spike shows platform behavior is subtle enough to warrant it.
- Implementers NEVER spawn their own subagents. Brief fidelity and the disjoint-file-set
  discipline both depend on the decision maker holding the only fan-out map. Parallel
  dispatches within a chunk are fine when file sets are disjoint.
- Review: one guided Opus dispatch per chunk boundary. The U4 core query (unread counts)
  is schema/repository plumbing and folds into U4's app review rather than its own.
- Every brief: locked decisions, STOP rule, acceptance criteria, timeless-comment rule,
  pointer to the relevant handoff sections.

## Cross-cutting rules (apply to every chunk)

- No hardcoded colors/type/spacing in screens after U1 — everything reads `MangoTheme`
  tokens via CompositionLocal. Reviews reject violations.
- Search-everywhere completeness holds: every new setting registers in `SETTINGS_ENTRIES`;
  every new one-off action gets a palette hit in the same chunk.
- Screenshot tests (`ScreenScreenshotsTest`, `ReaderScreenshotsTest`) are the visual
  regression net; each chunk refreshes the boards it changes and the diff is eyeballed
  by the owner before commit.
- Behavior-preserving restyles must keep existing flow tests green untouched; a flow test
  edit in a restyle chunk is a red flag the review specifically checks.
- No new dependencies. Theme JSON uses `kotlinx-serialization-json` (already in the
  catalog). If a blur effect would need a library, we take the spec's alpha fallback
  instead (overlay token at 86–92% opacity, no blur).

## U1 — Theme foundation

Goal: the token system exists, everything reads from it, themes round-trip as JSON.

Tasks:
1. `MangoTheme` immutable data class: color tokens, type ramp, spacing/radius scale,
   motion constants (decel/standard easings, named durations) — names and values exactly
   per handoff. Provided via CompositionLocal at the AppShell root; `MaterialTheme`
   mapping kept only where Material components still need it.
2. Theme JSON: serialize/deserialize the color token set (kotlinx-serialization).
   Import is a trust boundary: validate hex formats and required fields, reject unknown
   schema versions with a user-visible error, never crash on malformed input. Accent
   override recolors accent/accent.on/focus as a group.
3. Settings APPEARANCE group: accent swatch row + custom accent, theme Export .json /
   Import… rows. All registered in `SETTINGS_ENTRIES`.
4. Recolor sweep: replace every hardcoded color/`colorScheme` read across all screens
   with token reads. Zero layout changes — this chunk must produce screenshot diffs that
   are color-only.

File map: `Theme.kt` (rework), new `ThemeJson.kt`, `Settings.kt` + `SettingsScreen.kt`
(new entries), then the sweep touches every screen file.

Dispatch: one Sonnet for tasks 1–3 (theme core + settings, single coherent brief).
After it lands, the recolor sweep fans out to 2–3 Haiku/Sonnet dispatches on disjoint
screen-file groups (e.g. Library+Browse+Search / Details+Reader / Downloads+Extensions+
Palette+ChallengeUi). Sweep briefs are mechanical: a token-mapping table, no judgment.

Acceptance: JSON round-trip test (export → import → identical theme); malformed-import
rejection test; settings completeness tests pass with the new entries; both suites green;
screenshots show recolor only.

Risks/locked: accent persistence lives in the existing `settings.properties` mechanism;
full theme files are explicit export/import only (no theme directory scanning — YAGNI).

## U2 — Window chrome + sidebar

Goal: borderless window with custom title bar; the Arc-style overlay sidebar.

Spike first (Fable, inline, before any brief): undecorated `ComposeWindow` on Windows —
drag region, minimize/maximize/restore, double-click-titlebar, and what we lose (native
snap/Aero shake). Also the blur question: in-app backdrop blur behind overlay panels is
not free in Compose Desktop; candidate is blurring the content layer beneath when an
overlay is open (works for full-screen backdrops like the palette; wrong for the
sidebar, which covers a strip — fallback there is the overlay token at high alpha, which
the spec's 78–86% range explicitly tolerates). Spike output: locked decisions in the U2
brief, and the choice of Sonnet vs a single Opus implementer for the chrome piece.
JCEF interplay must be checked in the spike too: the challenge-solve webview is a
heavyweight component; verify overlays (sidebar, palette) composite above it or record
the constraint.

Tasks:
1. Undecorated window + custom 44dp title bar: drag region, right-side window controls
   with hover states, left-side sidebar-toggle glyph.
2. Overlay sidebar per spec: geometry, Continue-reading cards (top 3 in-progress series —
   reuses the read-progress data that drives the existing Continue action), nav items
   with active state, Downloads count pill. Overlays content, never pushes.
3. Ctrl+B hotkey + "Toggle sidebar" palette action.

File map: `Main.kt` (window), new `Chrome.kt` (title bar + sidebar), `AppShell.kt`
(overlay wiring, nav state), `Palette.kt` provider registration only.

Dispatch: chrome to Sonnet or Opus per spike outcome; sidebar contents to Sonnet.
Sequential (chrome first) — same files.

Acceptance: Ctrl+B toggles (flow test); palette action present (completeness test);
sidebar Continue click resumes the right chapter (flow test reusing existing fakes);
screenshot boards for open/closed states; existing key handling (double-Shift, reader
keys) unaffected.

### Spike findings (2026-07-12, verified on the owner's machine) — decisions LOCKED

- Chrome mechanism: **JBR `WindowDecorations.CustomTitleBar`** (the IntelliJ approach),
  NOT `undecorated = true`. The window keeps a real native title bar merged into the
  Compose canvas: native drag, double-click maximize, Win+Left/Right, Win11 snap
  layouts, edge resize, and OS-drawn min/max/close all verified working. Vanilla
  undecorated windows lose Win+arrow snap (OS refuses without a native bar) — rejected.
- Compose needs the **forceHitTest handshake**: Compose is one AWT canvas, so every
  unconsumed pointer event over the bar must call `CustomTitleBar.forceHitTest(false)`
  (else the whole strip hit-tests as client and drag/double-click die); consumed events
  (a child control in use) report `true` until release. Verified: bar drag + an
  interactive glyph in the bar coexist. Proven pattern (from the spike, Jewel-style):

  ```kotlin
  fun Modifier.jbrHitTest(bar: CustomTitleBar?) = pointerInput(bar) {
      bar ?: return@pointerInput
      awaitPointerEventScope {
          var inControl = false
          while (true) {
              val event = awaitPointerEvent(PointerEventPass.Main)
              val consumed = event.changes.any { it.isConsumed }
              if (consumed || inControl) {
                  if (event.type == PointerEventType.Press) inControl = true
                  if (event.type == PointerEventType.Release) inControl = false
                  bar.forceHitTest(true)
              } else bar.forceHitTest(false)
          }
      }
  }
  ```

- Window controls: **native, OS-drawn** (right inset reported 141px @ 44dp bar height —
  reserve it via `getRightInset()`, don't hardcode). The handoff's custom-drawn
  min/max/close are superseded; the left-side sidebar glyph stays ours.
- Runtime requirement: the app must RUN on a JetBrains Runtime for merged chrome.
  Dev-run works by pointing `compose.desktop.application.javaHome` at a JBR (spike used
  the IDEA-bundled JBR 25). Distribution needs a JBR **SDK** (jmods for jpackage) —
  a packaging-time concern, tracked for the packaging chunk, not U2.
- Graceful fallback is mandatory: JBR API absent (stock JDK) → normal OS-decorated
  window with our 44dp bar rendered below the OS bar. Tests run on stock JDK 17, so the
  fallback path is what CI exercises; JBR access goes through one small reflective shim
  (no new dependency) or `jbr-api` pinned in the catalog — implementer brief locks the
  shim, reflection stays in one file.
- Blur: measured free (avg 4.17–4.51ms/frame with 24dp blur over animated content, worst
  12ms). Owner decision: **sidebar is alpha-only (86%); backdrop blur is reserved for
  the command palette** (and possibly dialogs). The handoff's "blur behind sidebar"
  line is overridden.
- JCEF: challenge webview lives in its own `JFrame` (JcefManager), so overlays never
  compete with the heavyweight browser. Non-issue.
- U2 implementer: Sonnet is sufficient — the platform-subtle part is solved and
  documented above; remaining work is mechanical against this spec.

## U3 — Shared component kit

Goal: the reusable primitives every later chunk consumes.

Tasks: skeleton shimmer, empty state, error banner (generalized from the Extensions
banner), challenge cards (restyle `ChallengeUi.kt` to warning-token spec), button
variants (primary/secondary/ghost/danger), pills, keycaps, progress tracks, segmented
control, cover card (rest/hover/finished states).

File map: new `Kit.kt` (or a small `kit/` package if it crowds), `ChallengeUi.kt`.
Extensions' banner call-site swaps to the kit banner in the same chunk (proof of reuse).

Dispatch: one Sonnet — the pieces are small and interlocking; splitting buys nothing.

Acceptance: a kit screenshot board (one test page rendering every primitive in every
state — this is the visual contract later chunks build on); Extensions banner flow test
still green on the swapped component.

## U4 — Library

Goal: cover grid per spec + the new list view + unread counts.

Tasks:
1. Core: per-series unread count query (chapters without a read_progress row) exposed on
   the library repository. Schema/plumbing only.
2. Grid restyle on the kit cover card: hover scrim + progress, unread pill, finished
   treatment.
3. List view + grid/list segmented toggle; view choice persists as a setting
   (`SETTINGS_ENTRIES`) and gets a "Toggle library view" palette hit.

File map: core repository + `.sq` (task 1) | `LibraryScreen.kt` (tasks 2–3) — disjoint,
so tasks 1 and 2–3 dispatch in parallel (Sonnet each); task 3's toggle wiring waits on
nothing from core.

Acceptance: unread-count query unit test (fixture rows); grid/list toggle flow test;
completeness tests for setting + action; screenshots for grid, list, hover, finished.

## U5 — Reader

Goal: fully immersive reading with the overlay and no-reflow loading.

Tasks:
1. Overlay rework to spec: mouse-move reveals (replacing the current top-edge hover
   band), 1.5s idle fade-out, cursor hides with it; floating bottom bar (chapter title,
   panel progress, prev/next, auto-scroll pill, progress bar); top-left back button.
   Existing behaviors preserved: auto-scroll pause rules, palette interplay, N/P keys.
2. No-reflow placeholders: pre-reserved height (source metadata when present, 3:4
   fallback — ponytail ceiling), shimmer, 200ms crossfade on load. Prefetch pipeline
   untouched.

File map: `ReaderScreen.kt` (+ extracted pure idle-timer/overlay-state helper for
testing).

Dispatch: one Sonnet with a tight brief; overlay-state logic is extracted pure and
unit-tested against the test-harness clock (same pattern as the auto-scroll work).
Escalate to Opus only if the brief bounces once.

Acceptance: overlay state unit tests (reveal on move, hide after idle, pinned while
palette open per existing rules); ReaderScreenshots refreshed; reader flow tests green;
no scroll-position jump on image load (existing anchor tests still pass).

Risk: mouse-move reveal fires constantly during scroll (wheel moves the pointer's hover
target). The brief locks the rule: reveal on pointer *movement*, not scroll delta; the
pure helper makes this testable.

## U6 — Remaining screens + palette

Goal: Details, Browse, Search, Downloads, Extensions, Settings restyled on the kit;
palette gets Spotlight styling.

Carried from the U1 review (deferred cosmetics): Settings accent swatches to spec —
26 dp, accent-colored selected ring with bg gap, dashed "+" custom swatch.

Tasks: per-screen restyles to spec (all behavior-preserving, kit components mandatory),
then the palette panel: centered geometry, backdrop treatment (per the U2 blur
decision), filter-tab pills, row/keycap styling, footer hints. Palette behavior
(double-Shift, ranking, tabs, registry) does not change.

File map: `DetailsScreen.kt` | `BrowseScreen.kt` + `SearchScreen.kt` |
`DownloadsScreen.kt` + `ExtensionsScreen.kt` | `SettingsScreen.kt` — four disjoint
parallel Sonnet dispatches. `Palette.kt` restyles afterward as its own single Sonnet
dispatch (it overlays everything; visual QA against the finished screens).

Acceptance: all existing flow tests green untouched; screenshots refreshed per screen;
palette flow tests green; challenge states render via kit cards on Details/Browse/
Reader/Search (already wired through ChallengeUi).

## Sequencing

U1 → U2 → U3 strictly sequential (each is a foundation for the next). U4, U5, U6 are
then independent of each other and run in order of owner interest; internal parallelism
as mapped above. The U2 spike is the only scheduled unknown; its fallback (keep OS
chrome) de-scopes nothing else.
