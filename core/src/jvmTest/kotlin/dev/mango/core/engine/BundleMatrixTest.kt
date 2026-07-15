package dev.mango.core.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Offline boot/surface check across the full bundle matrix (FlameComics, MangaBat,
 * Synthetic): extends BundleBootTest's pattern, which stays FlameComics-only, over every
 * source. Zero network — verify + evaluate + initialise() + method surface only.
 */
class BundleMatrixTest {
    private fun verifyBootSurface(sourceId: String, bundleJs: String) = runBlocking {
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
        verifyBootSurface("FlameComics", BundleLoader.verify(readFixture(FLAME_COMICS_FIXTURE), FLAME_COMICS_SHA256))

    @Test
    fun mangaBatBootsAndExposesSurface() =
        verifyBootSurface("MangaBat", BundleLoader.verify(readFixture(MANGABAT_FIXTURE), MANGABAT_SHA256))

    @Test
    fun syntheticBootsAndExposesSurface() =
        verifyBootSurface("Synthetic", syntheticBundle)
}
