# K1 — Kit consolidation chunk

Goal: stop reinventing UI primitives. Extract what exists 3+ times, delete dead code,
adopt the type ramp. No visual redesign — current screenshots are the reference; the
app must render identically (or imperceptibly close) after every wave.

Sources: duplication scan + token audit + IntelliJ inspections, 2026-07-12. All three
agree on the same top offenders.

## Locked decisions

- Extraction threshold: 3+ near-identical copies. Two loosely similar copies stay inline.
- Text sizes are decisions, not drift: adopting MangoType styles must preserve the exact
  current numeric values. New ramp steps get added for recurring sizes; nothing is snapped
  to a different size to "fit the grid".
- Off-grid spacing literals (10/18/6/24dp paddings) are NOT re-snapped. Extraction absorbs
  them; the rest stay.
- Structural one-offs (300dp cover column, palette panel width, grid minimums) stay raw;
  the only change is deduplicating repeats within a file into a local constant.

## Wave 1 — foundations (Kit.kt, Theme.kt, ChallengeUi.kt)

1. `rememberHoverFill(rest, hover)` in Kit.kt: owns the interaction source + the
   zero-alpha animated fill idiom currently pasted in 10 places. The "why not
   Color.Transparent" explanation moves into its KDoc — the one place it lives.
2. `MangoRadius.pill` (999.dp idiom, currently raw in 5 files).
3. `KitSearchField` in Kit.kt: the StyledSearchField currently pasted byte-for-byte in
   BrowseScreen + SearchScreen (IntelliJ: 39-line duplicate). Placeholder stays a real
   parameter (the two callers pass different strings).
4. `ChallengeErrorContent` gains optional `onRetry` (default null → no Retry button);
   delete dead `ChallengeFailedCard` + `ChallengeSolvingCard` from Kit.kt (zero callers —
   verify including tests before deleting).
5. Delete dead `MangoMotion.VIEW_CHANGE_MS` / `VIEW_CHANGE_RISE`; fix the broken
   `[ColorScheme]` KDoc link in Theme.kt.
6. Typography catalog (report only, no screen edits): every inline fontSize/weight/style
   tuple in the app package with counts → proposed named MangoType additions (3+ rule,
   exact values preserved) for owner sign-off before wave 2 adopts.

## Wave 2 — adoption (parallel, disjoint file sets; needs wave-1 API + signed-off ramp)

- A: BrowseScreen, SearchScreen — use KitSearchField (delete both private copies and the
  duplicated radius constants), rememberHoverFill, MangoType adoption, operator-assignment
  lint fixes, drop the always-same `placeholder` args.
- B: DetailsScreen, ReaderScreen — rememberHoverFill (ChapterRow, ChapterDownloadGlyph),
  replace ReaderScreen's inlined challenge card with ChallengeErrorContent(onRetry=…),
  dedupe the 21-line centered-challenge wrapper, 300dp/48dp local constants, MangoType
  adoption, Duration overload fixes, inline `val latest` cleanup.
- C: Chrome, LibraryScreen, SettingsScreen, DownloadsScreen, ExtensionsScreen, Palette,
  Main — rememberHoverFill (5 sites), wire sidebar badge + GenreChip-style pills to Kit
  `Pill`, share TITLE_BAR_HEIGHT (44dp declared twice), `tween(160)` →
  `MangoMotion.COVER_HOVER_MS`, raw radius literals → MangoRadius tokens, delete dead
  `leftInset` (Chrome.kt:88), MangoType adoption.

## Wave 3 — verification

- `:app:jvmTest` green; wide-window + standard screenshots re-rendered and eyeballed
  against pre-chunk renders (no layout drift).
- `/code-review` pinned to Opus at the chunk boundary.

## Explicitly out of scope

- HoverListRow extraction (re-evaluate after rememberHoverFill lands — remaining
  duplication may be too thin to justify a slotted component).
- ScreenHeader, icon tiles, icon-button unification (superficially similar, genuinely
  different).
- AppShell's documented-unreachable `Screen.Reader` branch (defensive exhaustiveness).
- Any spacing-grid renumbering.
