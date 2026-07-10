package dev.squidwha.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleBootTest {
    private val bundleJs =
        BundleLoader.verify(readFixture(FLAME_COMICS_FIXTURE), FLAME_COMICS_SHA256)

    @Test
    fun exposesPreInstantiatedExtension() = runBlocking {
        ExtensionRuntime(bundleJs).withExtension { handle ->
            val extension = handle.extension("FlameComics")
            assertTrue(extension.hasMembers(), "expected a pre-instantiated extension object")
            assertTrue(
                handle.source.getMember("FlameComicsExtension").canInstantiate(),
                "expected the extension class alongside the instance",
            )
        }
    }

    @Test
    fun initialiseCompletesAndRegistersInterceptors() = runBlocking {
        ExtensionRuntime(bundleJs).withExtension { handle ->
            handle.invokeAwait(handle.extension("FlameComics"), "initialise")
            // FlameComics registers rate-limit, cookie, and API interceptors
            assertEquals(3, handle.registeredInterceptors)
        }
    }

    @Test
    fun implementsTheMethodsItsManifestCapabilitiesPromise() = runBlocking {
        val manifest = Json.parseToJsonElement(
            readFixture("versioning.json").decodeToString()
        ).jsonObject
        val flame = manifest["sources"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == "FlameComics" }
        assertTrue(flame["capabilities"]!!.jsonArray.isNotEmpty())

        ExtensionRuntime(bundleJs).withExtension { handle ->
            val extension = handle.extension("FlameComics")
            val methods = listOf(
                "initialise",
                "getSearchResults",
                "getMangaDetails",
                "getChapters",
                "getChapterDetails",
            )
            for (method in methods) {
                assertTrue(extension.canInvokeMember(method), "extension is missing $method")
            }
        }
    }
}
