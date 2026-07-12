package dev.mango.app

import dev.mango.core.domain.Chapter
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.LibraryItem
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.SourceInfo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Screenshots for the screens: rendered at 1280x800 with canned data via the content-level
 * composables, never asserted byte-exact.
 */
class ScreenScreenshotsTest {
    @Test
    fun libraryEmpty() {
        val file = Screenshots.render("library-empty") {
            MangoTheme { LibraryScreenContent(items = emptyList(), onOpenDetails = {}) }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun libraryPopulated() {
        val titles = listOf(
            "Solo Leveling",
            "Omniscient Reader",
            "Tower of God",
            "The Beginning After the End",
            "Nano Machine",
            "Return of the Mount Hua Sect",
        )
        val items = titles.mapIndexed { index, title ->
            LibraryItem(
                entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-$index", title = title),
                addedAt = Clock.System.now(),
            )
        }
        val file = Screenshots.render("library-populated") {
            MangoTheme { LibraryScreenContent(items = items, onOpenDetails = {}) }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun browseResults() {
        val sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat"))
        val results = listOf(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Omniscient Reader"),
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-3", title = "Tower of God"),
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-4", title = "Nano Machine"),
        )
        val file = Screenshots.render("browse-results") {
            MangoTheme {
                BrowseScreenContent(
                    sources = sources,
                    selectedSourceId = "FlameComics",
                    onSelectSource = {},
                    query = "solo",
                    onQueryChange = {},
                    onSearch = {},
                    searchActive = true,
                    isLoading = false,
                    error = null,
                    results = results,
                    sections = emptyList(),
                    sectionsLoading = false,
                    sectionsError = null,
                    onOpenDetails = {},
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun browseSections() {
        val sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat"))
        val popular = HomeSection(
            id = "popular",
            title = "Popular",
            items = listOf(
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Omniscient Reader"),
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-3", title = "Tower of God"),
            ),
        )
        val latest = HomeSection(
            id = "latest",
            title = "Latest Updates",
            items = listOf(
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-4", title = "Nano Machine"),
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-5", title = "Return of the Mount Hua Sect"),
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-6", title = "The Beginning After the End"),
                MangaEntry(sourceId = "FlameComics", mangaId = "manga-7", title = "Overgeared"),
            ),
        )
        val file = Screenshots.render("browse-sections") {
            MangoTheme {
                BrowseScreenContent(
                    sources = sources,
                    selectedSourceId = "FlameComics",
                    onSelectSource = {},
                    query = "",
                    onQueryChange = {},
                    onSearch = {},
                    searchActive = false,
                    isLoading = false,
                    error = null,
                    results = emptyList(),
                    sections = listOf(popular, latest),
                    sectionsLoading = false,
                    sectionsError = null,
                    onOpenDetails = {},
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun searchResults() {
        val sources = listOf(SourceInfo("FlameComics", "FlameComics"), SourceInfo("MangaBat", "MangaBat"))
        val results = listOf(
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
            MangaEntry(sourceId = "FlameComics", mangaId = "manga-2", title = "Omniscient Reader"),
        )
        val file = Screenshots.render("search-results") {
            MangoTheme {
                SearchScreenContent(
                    sources = sources,
                    enabledSourceIds = setOf("FlameComics", "MangaBat"),
                    onToggleSource = {},
                    query = "solo",
                    onQueryChange = {},
                    onSearch = {},
                    pendingSourceIds = emptySet(),
                    resultsBySource = mapOf("FlameComics" to results),
                    errorsBySource = mapOf("MangaBat" to "This source is protected by Cloudflare"),
                    challengeUrlsBySource = mapOf("MangaBat" to "https://mangabat.example/challenge"),
                    onOpenDetails = {},
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun details() {
        val details = MangaDetails(
            entry = MangaEntry(sourceId = "FlameComics", mangaId = "manga-1", title = "Solo Leveling"),
            authors = listOf("Chugong", "Redice Studio"),
            description = "Ten years ago, after \"the Gate\" that connected the real world with the " +
                "monster world opened, some of the ordinary, everyday people received the power to " +
                "hunt monsters within the Gate. They are known as \"Hunters\". Sung Jinwoo, the " +
                "weakest hunter of all mankind, finds himself in a mysterious double dungeon one day. " +
                "There he discovers a hidden system that only he can see and use.",
            status = MangaStatus.ONGOING,
            tags = listOf("Action", "Fantasy", "Adventure", "Drama"),
        )
        val chapters = (1..8).map { number ->
            Chapter(
                chapterId = "ch-$number",
                number = number.toDouble(),
                title = "Chapter $number",
                publishedAt = Clock.System.now(),
            )
        }
        val file = Screenshots.render("details") {
            MangoTheme {
                DetailsScreenContent(
                    details = details,
                    chapters = chapters,
                    inLibrary = false,
                    onToggleLibrary = {},
                    onOpenChapter = { _, _ -> },
                    onDownloadChapter = { _, _ -> },
                    onDownloadAll = { _, _ -> },
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun settings() {
        val file = Screenshots.render("settings") {
            MangoTheme {
                SettingsScreenContent(
                    themeNames = Themes.schemes.keys.toList(),
                    currentTheme = Themes.DEFAULT,
                    onSelectTheme = {},
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }

    @Test
    fun paletteOpen() {
        val hits = listOf(
            PaletteHit(category = "Screens", title = "Library", run = {}),
            PaletteHit(category = "Screens", title = "Browse", run = {}),
            PaletteHit(category = "Themes", title = "Theme: midnight", run = {}),
            PaletteHit(category = "Manhwa", title = "Solo Leveling", subtitle = "FlameComics", run = {}),
            PaletteHit(category = "Manhwa", title = "Tower of God", subtitle = "FlameComics", run = {}),
        )
        val file = Screenshots.render("palette-open") {
            MangoTheme {
                PaletteContent(
                    tabNames = listOf("All", "Manhwa", "Actions"),
                    activeTabIndex = 0,
                    onTabIndexChange = {},
                    query = "o",
                    onQueryChange = {},
                    hits = hits,
                    selectedIndex = 2,
                    onSelectedIndexChange = {},
                    onRunHit = {},
                    onDismiss = {},
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }
}
