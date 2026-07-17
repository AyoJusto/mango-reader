package dev.mango.app

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

/**
 * [PolicyImageFetcher] is the fix for reader pages and covers 403ing through Coil on
 * Cloudflare-walled sources: Coil's own network fetcher lowercases header names on the wire,
 * and this fetcher must send them exactly as [policyHeaders] built them.
 */
class PolicyImageFetcherTest {
    private val policyRequestHeaders = mapOf(
        "User-Agent" to "X/1.0",
        "Cookie" to "a=b",
        "Referer" to "https://s/",
    )

    private fun tinyPngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    // No disk cache: these tests assert MockEngine call counts, and Coil's Builder() default
    // (a real singleton disk cache) would let a hit on a prior run's cached bytes silently skip
    // the network call. The disk-cache tests below build their own loader with an explicit cache.
    private fun loaderWith(engine: MockEngine): ImageLoader =
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(PolicyImageFetcher.Factory(HttpClient(engine))) }
            .diskCache(null)
            .build()

    private fun requestWithPolicyHeaders(): ImageRequest =
        ImageRequest.Builder(PlatformContext.INSTANCE)
            .data("https://example.test/cover.jpg")
            .policyHeaders(policyRequestHeaders)
            .build()

    @Test
    fun sendsHeaderNamesVerbatimNotCoilsLowercasedCasing() {
        val recorded = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            recorded += request
            respond(tinyPngBytes(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }

        val result = runBlocking { loaderWith(engine).execute(requestWithPolicyHeaders()) }
        assertIs<SuccessResult>(result)

        // Name equality, not a case-insensitive lookup: a case-insensitive get would pass even
        // if the names had been lowercased, which is exactly the bug this fetcher exists to avoid.
        val names = recorded.single().headers.entries().map { it.key }.toSet()
        assertTrue("User-Agent" in names, "expected the literal name \"User-Agent\", got $names")
        assertTrue("Cookie" in names, "expected the literal name \"Cookie\", got $names")
        assertTrue("Referer" in names, "expected the literal name \"Referer\", got $names")
        assertEquals("X/1.0", recorded.single().headers["User-Agent"])
        assertEquals("a=b", recorded.single().headers["Cookie"])
        assertEquals("https://s/", recorded.single().headers["Referer"])
    }

    @Test
    fun aSuccessfulResponseProducesASuccessResult() {
        val engine = MockEngine {
            respond(tinyPngBytes(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }

        val result = runBlocking { loaderWith(engine).execute(requestWithPolicyHeaders()) }

        assertIs<SuccessResult>(result)
    }

    @Test
    fun aNonTwoHundredResponseProducesAnErrorResult() {
        val engine = MockEngine { respond(ByteArray(0), HttpStatusCode.Forbidden) }

        val result = runBlocking { loaderWith(engine).execute(requestWithPolicyHeaders()) }

        assertIs<ErrorResult>(result)
    }

    @Test
    fun aRequestWithoutPolicyHeadersIsNotClaimedByTheFactory() {
        val factory = PolicyImageFetcher.Factory(HttpClient(MockEngine { error("must not be called") }))
        val options = Options(context = PlatformContext.INSTANCE)
        val throwawayLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()

        val fetcher = factory.create("https://example.test/cover.jpg".toUri(), options, throwawayLoader)

        assertNull(fetcher)
    }

    @Test
    fun aSecondFetchOfTheSameUrlIsServedFromTheDiskCacheNotASecondNetworkRequest() {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond(tinyPngBytes(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }
        val diskCache = DiskCache.Builder()
            .directory(Files.createTempDirectory("policy-image-disk-cache-test").toString().toPath())
            .build()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(PolicyImageFetcher.Factory(HttpClient(engine))) }
            .diskCache(diskCache)
            .build()
        // Memory cache disabled so the second execute() can only be satisfied by the disk cache
        // (or a second network request) — a memory hit would make this test pass for the wrong
        // reason.
        val request = ImageRequest.Builder(PlatformContext.INSTANCE)
            .data("https://example.test/cover.jpg")
            .policyHeaders(policyRequestHeaders)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

        val first = runBlocking { loader.execute(request) }
        val second = runBlocking { loader.execute(request) }

        assertIs<SuccessResult>(first)
        assertIs<SuccessResult>(second)
        assertEquals(1, requestCount, "expected the second fetch to be served from Coil's disk cache")
    }

    @Test
    fun aNullDiskCacheStillProducesASuccessResult() {
        val engine = MockEngine {
            respond(tinyPngBytes(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(PolicyImageFetcher.Factory(HttpClient(engine))) }
            .diskCache(null)
            .build()

        val result = runBlocking { loader.execute(requestWithPolicyHeaders()) }

        assertIs<SuccessResult>(result)
    }
}
