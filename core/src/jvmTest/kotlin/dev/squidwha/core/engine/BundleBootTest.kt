package dev.squidwha.core.engine

import com.dokar.quickjs.evaluate
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
        ExtensionRuntime(bundleJs).withExtension { qjs ->
            assertTrue(
                qjs.evaluate(
                    "typeof source === 'object' && " +
                        "typeof source.FlameComics === 'object' && " +
                        "typeof source.FlameComicsExtension === 'function'"
                )
            )
        }
    }

    @Test
    fun initialiseCompletesAndRegistersInterceptors() = runBlocking {
        ExtensionRuntime(bundleJs).withExtension { qjs ->
            qjs.evaluate<Any?>("source.FlameComics.initialise()")
            // FlameComics registers rate-limit, cookie, and API interceptors
            assertEquals(3, qjs.evaluate<Int>("Application.__interceptorCount()"))
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

        ExtensionRuntime(bundleJs).withExtension { qjs ->
            val methods = listOf(
                "initialise",
                "getSearchResults",
                "getMangaDetails",
                "getChapters",
                "getChapterDetails",
            )
            for (method in methods) {
                assertTrue(
                    qjs.evaluate("typeof source.FlameComics.$method === 'function'"),
                    "extension is missing $method",
                )
            }
        }
    }
}
