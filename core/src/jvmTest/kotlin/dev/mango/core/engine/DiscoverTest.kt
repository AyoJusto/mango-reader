package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Offline tests for [PaperbackExtension.getHomeSections], replayed from recorded fixtures
 * plus synthetic inline bundles for the edge cases that need no network at all.
 *
 * WebtoonXYZ is excluded from the replay loop: the site is Cloudflare-challenged and has
 * no recorded fixtures anywhere in the suite (same as search); its challenge coverage
 * lives in [LiveDetectWebtoonXyzTest].
 */
class DiscoverTest {
    private val replayBundles = listOf(
        "FlameComics" to flameComicsBundle,
        "MangaBat" to mangaBatBundle,
        "Toonily" to toonilyBundle,
    )

    @Test
    fun homeSectionsReplayFromRecordedFixtures() = runBlocking {
        for ((sourceId, bundleJs) in replayBundles) {
            val sections = PaperbackExtension(
                sourceId, bundleJs, ApplicationHost(http = RecordedHttp.replayClient())
            ).getHomeSections()
            assertTrue(sections.isNotEmpty(), "[$sourceId] expected at least one home section")
            for (section in sections) {
                assertTrue(section.id.isNotBlank(), "[$sourceId] section has blank id: $section")
                assertTrue(section.title.isNotBlank(), "[$sourceId] section '${section.id}' has blank title")
                for (item in section.items) {
                    assertEquals(sourceId, item.sourceId, "[$sourceId] item has wrong sourceId: $item")
                    assertTrue(item.mangaId.isNotBlank(), "[$sourceId] item has blank mangaId: $item")
                    assertTrue(item.title.isNotBlank(), "[$sourceId] item has blank title: $item")
                }
            }
            assertTrue(
                sections.any { s -> s.items.any { it.cover != null } },
                "[$sourceId] expected at least one item with a non-null cover",
            )
        }
    }

    @Test
    fun genresSkipDedupeAndEmptyDropArePinned() = runBlocking {
        // the genres "a" (type 4) is skipped BEFORE dedupe, so the real "a" survives;
        // "b dup" is deduped (first wins); "c" is dropped because it normalizes to empty;
        // "m-" + section.id proves the original stub Value was passed back to the bundle
        val js = """
            var source = { Mini: {
                initialise: async () => {},
                getDiscoverSections: async () => [
                    { id: "a", title: "A genres", type: 4 },
                    { id: "a", title: "A", type: 1 },
                    { id: "b", title: "B", type: 1 },
                    { id: "b", title: "B dup", type: 1 },
                    { id: "c", title: "C", type: 1 },
                ],
                getDiscoverSectionItems: async (section) =>
                    section.id === "c" ? { items: [] }
                                       : { items: [{ mangaId: "m-" + section.id, title: "T" }] },
            } };
        """.trimIndent()
        val sections = PaperbackExtension("Mini", js, ApplicationHost()).getHomeSections()
        assertEquals(listOf("a", "b"), sections.map { it.id })
        assertEquals(listOf("A", "B"), sections.map { it.title })
        assertEquals(listOf("m-a", "m-b"), sections.map { it.items.single().mangaId })
    }

    @Test
    fun stubCountIsCapped() = runBlocking {
        val js = """
            var source = { Mini: {
                initialise: async () => {},
                getDiscoverSections: async () =>
                    Array.from({length: 40}, (_, i) => ({id: "s"+i, title: "S"+i, type: 1})),
                getDiscoverSectionItems: async () => ({ items: [{ mangaId: "m", title: "T" }] }),
            } };
        """.trimIndent()
        val sections = PaperbackExtension("Mini", js, ApplicationHost()).getHomeSections()
        assertEquals(32, sections.size)
    }

    @Test
    fun nonObjectStubThrowsNamedError() = runBlocking {
        val js = """
            var source = { Mini: {
                initialise: async () => {},
                getDiscoverSections: async () => [null],
            } };
        """.trimIndent()
        assertFailsWith<ExtensionDataException> {
            PaperbackExtension("Mini", js, ApplicationHost()).getHomeSections()
        }
        Unit
    }

    @Test
    fun nonIntegerTypeIsIgnoredNotFatal() = runBlocking {
        // Graal's asInt throws on 4.5; the fitsInInt guard must treat it as "no type"
        val js = """
            var source = { Mini: {
                initialise: async () => {},
                getDiscoverSections: async () => [{ id: "a", title: "A", type: 4.5 }],
                getDiscoverSectionItems: async () => ({ items: [{ mangaId: "m", title: "T" }] }),
            } };
        """.trimIndent()
        val sections = PaperbackExtension("Mini", js, ApplicationHost()).getHomeSections()
        assertEquals(listOf("a"), sections.map { it.id })
    }

    @Test
    fun nonObjectItemsResultThrowsNamedError() = runBlocking {
        val js = """
            var source = { Mini: {
                initialise: async () => {},
                getDiscoverSections: async () => [{ id: "a", title: "A", type: 1 }],
                getDiscoverSectionItems: async () => [],
            } };
        """.trimIndent()
        assertFailsWith<ExtensionDataException> {
            PaperbackExtension("Mini", js, ApplicationHost()).getHomeSections()
        }
        Unit
    }

    @Test
    fun missingGetDiscoverSectionsFallsBackToEmpty() = runBlocking {
        val js = "var source = { Mini: { initialise: async () => {} } };"
        val sections = PaperbackExtension("Mini", js, ApplicationHost()).getHomeSections()
        assertEquals(emptyList(), sections)
    }

    @Test
    fun malformedItemThrowsNamedError() = runBlocking {
        val js = """
            var source = { Mini: {
                initialise: async () => {},
                getDiscoverSections: async () => [{ id: "s1", title: "Section One" }],
                getDiscoverSectionItems: async () => ({ items: [{ title: "no id" }] }),
            } };
        """.trimIndent()
        assertFailsWith<ExtensionDataException> {
            PaperbackExtension("Mini", js, ApplicationHost()).getHomeSections()
        }
        Unit
    }
}
