# Handoff: Mango — Desktop Manhwa Reader (Full App Visual Direction)

Source: claude.ai/design project "Mango Desktop Reader Lookbook"
(`ac79996a-9874-4f65-a2e3-86088d901c95`), file `design_handoff_mango_reader/README.md`.
The pannable lookbook (`Mango Lookbook.dc.html`, boards 01–11) and board screenshots
live in that project; this document is the spec of record for implementation.

## Overview
Mango is a desktop reader for long-strip vertical comics (manhwa/webtoons), to be built in **Kotlin Compose for Desktop** on Windows. This handoff covers the complete visual direction: theme token system, window chrome, the signature collapsible sidebar, eight screens (Library, Reader, Details, Browse, Search, Downloads, Extensions, Settings), a Double-Shift command palette, and shared loading/empty/error/challenge states.

Aesthetic: Apple-native calm on Windows — layered near-black backgrounds, one warm mango-amber accent, generous padding, 10–14 dp rounded corners, depth via elevation and in-app translucency rather than borders. Covers and artwork are the only saturated color besides the accent.

## About the Design Files
`Mango Lookbook.dc.html` is a **design reference created in HTML** — a lookbook showing intended look and behavior, not production code. The task is to **recreate these designs in Compose for Desktop** using Compose idioms (Modifier chains, `animate*AsState`, `AnimatedVisibility`, `LazyVerticalGrid`, etc.). Every value in this document was chosen to be Compose-portable: solid colors, dp radii, cubic-bezier easings, in-app blur only (no behind-window transparency).

Open the lookbook in a browser — it is a pannable canvas of 11 boards, numbered 01–11.

## Fidelity
**High-fidelity.** Colors, type sizes, spacing, radii, and motion values are final and should be matched exactly. Placeholder cover art (gradients) is not final — real cover images replace them.

## Design Tokens (the theme system — build this first)

The entire UI reads from a semantic token set. **Themes are user-exportable/importable JSON files**; each token below is a field in that file. Implement as an immutable `MangoTheme` data class provided via CompositionLocal; never hardcode a color in a screen.

Default theme "Mango Dark":

| Token | Value | Usage |
|---|---|---|
| `bg0` | `#0D0D0F` | Window base, reader letterbox, title bar |
| `bg1` | `#141417` | Content wells, settings groups, sidebar rows |
| `bg2` | `#1B1B1F` | Cards at rest, inputs, progress tracks |
| `surface` | `#232328` | Elevated: hover fills, secondary buttons, keycaps |
| `overlay` | `#18181C` @ 78–86% alpha + 24 dp blur | Floating chrome: palette, reader overlay, sidebar |
| `text.primary` | `#F4F4F6` | Titles, row primaries |
| `text.secondary` | `#F4F4F6` @ 64% | Metadata, inactive nav, descriptions |
| `text.tertiary` | `#F4F4F6` @ 40% | Hints, timestamps, micro labels |
| `divider` | `#FFFFFF` @ 6% | Hairlines — sparingly, inside groups only |
| `accent` | `#FFAD33` | Primary actions, progress, active states |
| `accent.on` | `#201302` | Text/icons on accent fills |
| `success` | `#3FCF8E` | Finished check, completed downloads |
| `warning` | `#F5C64B` | Challenge flow, stale source |
| `danger` | `#F26055` | Error banners, destructive buttons, close hover |
| `focus` | `#FFAD33` @ 45%, 2 dp ring | Keyboard focus ring, offset 2 dp from control by a bg gap |

Elevation = stepping bg0 → bg1 → bg2 → surface. Never borders for depth. Accent-tinted fills use accent at 12–16% alpha (e.g. active nav item, auto-scroll pill); danger fills at 10–16%.

### Type ramp
Segoe UI Variable (Windows) / SF Pro fallback; -webkit system stack in the lookbook.
- **Display** 28/34 Bold, −0.02em — page titles
- **Title** 20/26 Semibold, −0.01em — series titles, dialogs
- **Body strong** 14/20 Semibold — row primaries, buttons
- **Body** 14/20 Regular — default
- **Caption** 12/16 Regular, text.secondary — metadata
- **Micro label** 11, +0.12em ALL CAPS, text.tertiary — group headers
- Mono (keycaps, chapter numbers): Cascadia Code 11–12.5

### Space & radius
4-dp base grid. Padding steps **8 · 12 · 16 · 20 · 28**. Screen gutters 28.
Radii: **6** keycap · **10** control/button · **12** cover/list row · **14** card/window panel · **18** large panel (palette, token cards).

### Motion (all transitions)
Easings: `decel = CubicBezier(0.2, 0, 0, 1)`, `standard = CubicBezier(0.4, 0, 0.2, 1)`. Nothing bounces; nothing exceeds 320 ms.

- Hover states — 120 ms ease-out, fill/elevation only, no movement
- Press — 100 ms standard, scale 0.97
- Sidebar open — 240 ms decel, slide −100%→0 + fade; close 200 ms standard; contents stagger 20 ms/group capped at 60 ms
- Palette open — 180 ms decel, scale 0.98→1 + fade; backdrop dim/blur 240 ms
- Reader overlay in — 160 ms ease-out, opacity only; cursor shown with it
- Reader overlay out — 320 ms standard, after 1.5 s idle; cursor hides with it
- View change — 200 ms decel, crossfade + 8 dp rise on incoming view
- Cover hover — 160 ms decel, scale 1.03 + shadow
- Progress bars — 300 ms standard, animate width
- Banner in — 200 ms decel, slide down + fade; list below stays put

## Window Chrome (board 03)
Borderless window, custom title bar 44 dp tall, background = bg0 (blends into app — no OS strip; whole bar is a drag region except buttons).
- **Right**: standard Windows controls, each 44 dp wide: minimize (–), maximize (9×9 outline square), close (✕). Icon color text.secondary. Hover: surface fill 120 ms; close hover: danger fill.
- **Left, mirrored in the same row**: sidebar-toggle glyph (34×28 dp, radius 8, a 16 dp panel-with-left-rail icon). Active/open: surface fill + accent-colored icon. Reserve room for 1–2 future glyphs.

## Sidebar (board 03 — signature element)
Collapsible, hidden by default. Toggled by title-bar glyph or **Ctrl+B**.

**Decision: it OVERLAYS content, never pushes.** Rationale: content never reflows, reading position and grid layout stay pixel-stable, and it can appear over the zero-chrome reader without disturbing it.

Geometry: 264 dp wide, floats from top of content area (below title bar) to 10 dp from bottom, 10 dp left inset, radius 14. Fill: overlay token (`#18181C` @ 86% + 24 dp in-app blur), right-edge shadow `12dp 0 40dp rgba(0,0,0,0.45)`. Padding 16/12, row gap 4.

Contents top-to-bottom:
1. Micro label "CONTINUE READING", then up to 3 small cover cards: 30×40 cover (radius 6) + title (12.5 Semibold, ellipsized) + chapter (11, text.tertiary). One click resumes the exact chapter at saved scroll offset. Row hover: surface fill, radius 10.
2. Hairline divider (divider token, 10 dp vertical margin).
3. Nav items, 16 dp stroke icons + 13 sp label, padding 8/10, radius 10: **Library, Browse, Search, Downloads, Extensions, Settings**. Active item: accent @ 12% fill + accent text/icon. Downloads shows a count pill (surface fill, radius 999). Inactive: text.secondary; hover: surface fill.

Mid-transition reference: at t≈120 ms the panel sits at translateX −38%, alpha 0.55.

## Screens / Views

### 1. Library (board 04)
- Header: "Library" (Display 28) left; right: "24 series" (13, tertiary) + Grid/List segmented control (bg2 track radius 10, padding 3; selected segment surface fill radius 8 + small shadow).
- **Grid view**: adaptive columns = `floor(width / 196)`, gap 20, covers 2:3 radius 12. Beneath each: title (13 Semibold, ellipsized) + meta line (11.5 tertiary, e.g. "Ch. 142 · 72%"). Unread count: pill top-right on cover, accent @ 92% bg, accent.on text, 11 Bold, radius 999, 8 dp inset.
- **Cover states**: rest (unread pill only) · hover (scale 1.03 + shadow `0 12dp 32dp rgba(0,0,0,0.5)` + bottom scrim gradient to bg0 @ 85% showing "Ch. 142 · 72%" and a 3 dp accent progress bar) · **finished** (cover at 70% opacity, pill replaced by success ✓ pill: success @ 20% bg, success text; meta says "Completed").
- **List view**: rows padding 8/12 radius 10, hover bg1: 32×44 cover thumb · title+chapter block (250 dp) · flexible 4 dp progress track (accent fill; success fill at 100%) · right-aligned % (12) · last-read (12, tertiary, 110 dp).

### 2. Reader (board 05)
- Fully immersive: long-strip images centered at user-set strip width (default 880 px), bg0 letterbox either side, **zero chrome while reading**.
- On mouse-move, overlay fades in (160 ms): top-left back button (34×30, overlay fill + blur, radius 10, chevron-left) and a bottom-centered floating bar (520 dp wide, radius 14, overlay fill + 24 dp blur, shadow `0 16dp 48dp rgba(0,0,0,0.5)`, padding 12/16) containing: chapter title (13 Semibold) + "41 / 68 panels · 60%" (11.5 secondary); right cluster: prev/next chapter buttons (30×28, white @ 6% fill, radius 8) and an **Auto-scroll** toggle pill (accent @ 16% fill, accent text + 6 dp dot when on); below, full-width 3 dp progress bar (white @ 10% track, accent fill).
- Fades out after 1.5 s idle (320 ms); cursor hides with it. Esc or back button exits.
- **Loading placeholder (no reflow)**: pre-reserve the panel's known pixel height from source metadata (fallback 3:4 ratio); shimmer bg1→bg2 sweep, 1.8 s linear; loaded image crossfades over it in 200 ms. Scroll position never jumps.

### 3. Details (board 06)
Two columns, gap 40, gutters 48.
- Left (300 dp fixed): 2:3 cover radius 14 + shadow; **Continue — Ch. 142** primary button (38 dp, accent); row of "Mark finished" secondary + download icon-button (34 dp); metadata key/value list (12.5, keys tertiary, values secondary; Progress value in accent).
- Right: title (Display 28), genre chips (bg2, radius 999, padding 3/11, 12 secondary), description (14/1.6 secondary, max 680 dp), "197 chapters" (16 Semibold) + sort hint, then chapter rows: state dot (8 dp) · chapter number (mono 12, tertiary, 76 dp) · title (13.5 Medium, flexible, ellipsized) · state text · date (12 tertiary, 90 dp right-aligned). Row hover bg1, radius 10, padding 9/12.
- Chapter states: **in-progress** = accent dot + accent "reading · 60%" · **unread** = primary dot ("downloaded" sub in success when cached) · **read** = dot at 25% white, title drops to text.secondary.

### 4. Browse (board 07)
Source catalog. Header: source name (Title 22) + "Popular · updated 5 min ago" (12.5 tertiary); right: Popular/Latest/Filter pill tabs (active = accent fill + accent.on text). Cover grid, 5 columns, gap 18, same cover grammar as Library minus progress. Infinite scroll: footer row with 14 dp spinner (2 dp ring, accent top arc) + "loading page 3" hint; next page appends with 200 ms fade-in.

### 5. Search (board 07)
Cross-source. Search input: bg2, radius 12, padding 10/14, focus ring (2 dp accent @ 45%, 2 dp bg gap), magnifier icon, accent caret, "3 sources" count right. Results grouped by source with micro-label headers; rows: 30×42 thumb · title + "88 chapters · Action" sub · optional **In library** pill (success @ 14% bg, success text, 11 Semibold, radius 999).

### 6. Downloads (board 08)
Header + "Pause all" (secondary button) / "Clear done" (ghost). Rows: bg1 cards radius 12 padding 12/16: 30×42 thumb · title + chapter range · right state text (Downloading = accent, Queued = tertiary, Completed = success) · 4 dp progress (accent while active, success done) · sub-line "2 of 3 chapters · 46% · 1.2 MB/s" (11.5 tertiary).

### 7. Extensions (board 08)
Rows: 36 dp icon tile (radius 10) · name + "v1.4.12 · EN · 2 series in library" meta · right action button: **Remove** (danger @ 14% bg, danger text), **Update** (accent), **Configure** (surface).
**Inline error banner** (failure never blanks the screen): danger @ 10% fill radius 12, 8 dp danger dot, bold first sentence + secondary detail ("Your installed sources are untouched below."), **Retry** button (danger @ 16% bg, danger text) + dismiss ✕. Slides in above the list (200 ms decel); the list stays interactive.

### 8. Settings (board 09)
Micro-label group headers ("APPEARANCE", "READER", "GENERAL"); each group is a bg1 card radius 14; rows padding 14/18 with hairline dividers inset 18 dp between rows. Row = title (13.5 Semibold) + sub (12 tertiary) left, control right.
- **Theme row**: "Mango Dark · yours to edit" + **Export .json** / **Import…** buttons.
- **Accent row**: 26 dp color swatches (selected has 2 dp accent ring with bg gap) + dashed "+" custom swatch; changing accent recolors every accent token instantly.
- Reader group: Strip width slider (4 dp track, accent fill, 14 dp white thumb, mono value "880"), Auto-scroll speed, Hide-cursor toggle (38×22 pill, accent when on, 18 dp thumb slides 160 ms decel).
- General: palette hotkey keycaps, download location + Change… button.

## Command Palette (board 10)
Opens on **Double-Shift** anywhere; IntelliJ behavior, Spotlight styling.
- Backdrop: app content blurred ~10 dp + dim scrim bg0-black @ 55%, 240 ms.
- Panel: 660 dp wide, centered, top ≈120 dp; radius 18; fill `#1A1A1E` @ 88% + 32 dp in-app blur; shadow `0 32dp 90dp rgba(0,0,0,0.65)` + 1 dp inner white @ 7% ring. Opens 180 ms decel (scale 0.98→1 + fade).
- Input row: magnifier + 19 sp query text + accent caret + `esc` keycap right.
- Filter tabs: **All / Manga / Chapters / Actions / Settings** pills (active = accent fill, accent.on text); Tab cycles.
- Result rows: 26 dp-wide thumb (36 dp tall cover for manga/chapters; 26 dp glyph tile white @ 7% for actions/settings) · primary 13.5 Medium · secondary 11.5 @ 45% white · right-aligned keycap hint (white @ 7% bg, radius 6, mono 11). Selected row: accent @ 14% fill.
- Footer: hairline + "↑↓ navigate · ↵ open · tab next filter" hints and result count.
- Every app action and setting is registered here and executable directly.

## Shared States (board 11)
- **Loading**: skeleton shapes mirror final layout exactly, shimmer bg1→bg2 1.8 s linear; content replaces via 200 ms crossfade, never a pop.
- **Empty**: centered dashed 2:3 outline (white @ 20%), 15 Semibold title, 13 secondary guidance mentioning Shift-Shift, one accent CTA.
- **Error banner**: as specified in Extensions — reused anywhere a background action fails; never blanks existing content.
- **Challenge flow** (Cloudflare-protected source): step 1 inline bg2 card with spinner + "Solving site challenge…" + "~15 s" hint; step 2 on failure: **warning** (not danger — user did nothing wrong) card with warning dot, explanation, **Solve manually…** (accent, opens built-in webview) + **Retry** (surface). Appears inline where content would load; rest of screen keeps working.

## State Management (high level)
- `themeState`: current token set; accent override; import/export JSON.
- `sidebarOpen: Boolean` (default false), hotkey Ctrl+B.
- `libraryView: Grid | List`; per-series `unreadCount`, `progress`, `finished`.
- Reader: `stripWidth`, `autoScroll`, `overlayVisible` (mouse-move sets true + restarts 1.5 s idle timer), per-chapter saved scroll offset (drives "Continue reading").
- Palette: `open`, `query`, `activeFilter`, unified searchable registry of manga/chapters/actions/settings.
- Downloads queue: `Queued | Downloading | Completed` per item.
- Per-source challenge state: `Solving | NeedsManual | Ok`.

## Assets
No external assets. All covers in the lookbook are gradient placeholders — replace with real cover art from sources. Icons are simple 16 dp 1.5 dp-stroke line glyphs (drawn as inline SVG in the lookbook); use equivalent Compose vector icons.

## Files
- `Mango Lookbook.dc.html` — the full lookbook (boards 01–11). Open in a browser; pan/zoom the canvas. Board numbers match section references above.

---

# Addendum (2026-07-12): update checks, search history, collections

Extracted from the updated root `Mango Lookbook.dc.html` in the design project (the
`design_handoff_mango_reader/README.md` copy online predates these). Four additions.

## Library — update checks (board 04)

- Header right side becomes: "24 series · checked 2 h ago" (13, tertiary) · a 32×30 refresh
  icon-button (bg2 fill radius 10, hover surface; 14 dp circular-arrow stroke glyph,
  text.secondary) · the Grid/List segmented control. Checked-time hidden if never checked.
- Manual only — no automatic background checks (owner decision).
- When a check finds chapters for a series: the unread pill increments and pulses once
  (glow ring `0 0 0 3dp accent @ 25%`), and the card caption gains an amber prefix
  **"+n new"** (11 bold, accent) before the meta line ("+3 new · Ch. 142 · 72%"). The prefix
  persists until the user opens the series (Details visit), then clears.
- Fourth cover-states tile: rest pill · hover scrim · finished ✓ · **refresh found n** (pill
  with glow ring + bottom scrim carrying the "+3 new" caption).

## Details — update checks (board 06)

- Beside "197 chapters": "sorted newest first · checked 2 h ago" (12.5 tertiary) + the same
  32×30 refresh button (surface fill here). Refresh re-runs the live revalidation.
- Chapters that arrived since the last check wear a **NEW** chip after the title: accent @ 16%
  bg, accent text, 10.5 bold, +0.06em, padding 1.5/7, radius 999. Clears on open or next check.
- Row legend: accent dot in-progress · primary dot unread · tertiary dot read · NEW chip =
  arrived since last check.

## Search — recent queries (board 07, third frame)

- The search field focused but empty shows the last 10 queries: micro-label header row
  "RECENT" + "Clear all" (11.5 tertiary, hover primary); rows = 14 dp clock glyph (tertiary
  stroke) · query (13.5 primary, ellipsized) · relative time (11.5 tertiary) · 22 dp ✕
  (visible on row hover only, hover surface fill).
- Newest first, deduped — re-running a query moves it to the top. One click replays the
  search. Enter on a highlighted row also replays. Typing swaps the list for live results at
  the first keystroke; no history mixed into results. Plain rows, not chips.

## Collections (section 04b)

Model: a collection is a named shelf; a series can sit on several at once. "All" is virtual,
always first, uneditable. Exactly one collection is the **default** — it silently catches
one-click adds. Naming: **Collections** (recommended; verb reads "Add to collection"). Never
say "library" for a shelf.

- **Library nav — Option A (recommended, adopted):** chip tab row under the title:
  active chip accent fill + accent.on text ("All · 24"), inactive text.secondary with hover
  bg2, padding 5/13, radius 999; a "＋" tab creates a shelf inline. Counts always visible.
  Drag a cover onto a tab to file it; Ctrl 1–9 jumps tabs; overflow past ~8 shelves scrolls
  horizontally, never wraps. (Option B filter-dropdown rejected: hides shelves.)
- **Details add flow — split button (300 dp):** main segment "Add to library" (accent,
  radius 10 0 0 10) + 38 dp ▾ segment. One click files into the default immediately, no
  dialog, and shows a toast "Added to **Reading** · Change" (overlay fill, radius 12, 5 s;
  Change opens the picker). The ▾ opens a checkbox picker: micro-label "ADD TO", rows with
  15 dp checkboxes (checked = accent fill + ✓; row bg accent @ 10% when checked), DEFAULT
  badge (10.5 bold accent) on the default row, hairline, then "＋ New collection…". Once in
  library the button reads "In library ✓ ▾"; the same picker edits membership.
- **Manage collections dialog (520 dp, bg1, radius 18):** rows = ⠿ drag handle (drag sets
  tab order) · name (13.5 semibold; double-click renames inline — mid-edit row shows accent
  underline + focus ring) · count (12 tertiary) · DEFAULT pill or hover "Make default" ·
  delete ✕ (danger @ 80%). Deleting a collection never deletes series — they stay in All;
  if the default is deleted, the first shelf becomes default. Footer: "＋ New collection"
  left, accent "Done" right.
