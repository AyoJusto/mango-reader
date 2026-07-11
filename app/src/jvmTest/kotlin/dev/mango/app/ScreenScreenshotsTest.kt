package dev.mango.app

import dev.mango.core.domain.Chapter
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
 * Review-artifact screenshots for the M3.2 screens (M3 loop): rendered at 1280x800 with
 * canned data via the content-level composables, never asserted byte-exact.
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
                    isLoading = false,
                    error = null,
                    results = results,
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
                    onOpenChapter = {},
                )
            }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }
}
