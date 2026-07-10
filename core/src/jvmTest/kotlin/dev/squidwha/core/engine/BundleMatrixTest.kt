package dev.squidwha.core.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Offline boot/surface check across the full bundle matrix (FlameComics, MangaBat,
 * Toonily, WebtoonXYZ): extends BundleBootTest's pattern, which stays FlameComics-only,
 * over every source added in M1.4. Zero network — verify + evaluate + initialise() +
 * method surface only.
 */
class BundleMatrixTest {
    private fun verifyBootSurface(sourceId: String, fixture: String, sha256: String) = runBlocking {
        val bundleJs = BundleLoader.verify(readFixture(fixture), sha256)
        ExtensionRuntime(bundleJs).withExtension { handle ->
            val extension = handle.extension(sourceId)
            assertTrue(extension.hasMembers(), "[$sourceId] expected a pre-instantiated extension object")

            handle.invokeAwait(extension, "initialise")

            val methods = listOf(
                "initialise",
                "getSearchResults",
                "getMangaDetails",
                "getChapters",
                "getChapterDetails",
            )
            for (method in methods) {
                assertTrue(extension.canInvokeMember(method), "[$sourceId] extension is missing $method")
            }

            assertTrue(
                handle.registeredInterceptors >= 1,
                "[$sourceId] expected at least one interceptor registered after initialise()",
            )
        }
    }

    @Test
    fun flameComicsBootsAndExposesSurface() =
        verifyBootSurface("FlameComics", FLAME_COMICS_FIXTURE, FLAME_COMICS_SHA256)

    @Test
    fun mangaBatBootsAndExposesSurface() =
        verifyBootSurface("MangaBat", MANGABAT_FIXTURE, MANGABAT_SHA256)

    @Test
    fun toonilyBootsAndExposesSurface() =
        verifyBootSurface("Toonily", TOONILY_FIXTURE, TOONILY_SHA256)

    @Test
    fun webtoonXyzBootsAndExposesSurface() =
        verifyBootSurface("WebtoonXYZ", WEBTOONXYZ_FIXTURE, WEBTOONXYZ_SHA256)
}
