package dev.mango.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Offline proof that a Cloudflare challenge surfaces as [CloudflareChallengeException]
 * (never a generic [ExtensionCallException], never a hang) through the REAL bundles:
 * each source's own interceptResponse throws its in-bundle `type: "cloudflareError"`
 * error, and the engine boundary names it. MockEngine plays the challenged site.
 */
class ChallengeDetectionTest {
    @Test
    fun toonily403WithRecaptchaBodySurfacesAsCloudflareChallenge() = runBlocking {
        val challengedSite = HttpClient(MockEngine {
            respond(
                "<html>please solve this recaptcha to continue</html>".encodeToByteArray(),
                HttpStatusCode.Forbidden,
            )
        })
        val extension = PaperbackExtension("Toonily", toonilyBundle, ApplicationHost(http = challengedSite))
        assertFailsWith<CloudflareChallengeException> { extension.search("solo") }
        Unit
    }

    @Test
    fun mangaBatCfMitigatedHeaderSurfacesAsCloudflareChallenge() = runBlocking {
        val challengedSite = HttpClient(MockEngine {
            respond(
                "<html>checking your browser</html>".encodeToByteArray(),
                HttpStatusCode.OK,
                headersOf("cf-mitigated", "challenge"),
            )
        })
        val extension = PaperbackExtension("MangaBat", mangaBatBundle, ApplicationHost(http = challengedSite))
        assertFailsWith<CloudflareChallengeException> { extension.search("solo") }
        Unit
    }

    @Test
    fun wireCasedChallengeHeaderIsStillDetected() = runBlocking {
        // webtoon.xyz serves `Cf-Mitigated: challenge` (wire casing); bundles look up the
        // lowercase key, so the host must normalize or detection silently misses
        val challengedSite = HttpClient(MockEngine {
            respond(
                "<html>checking your browser</html>".encodeToByteArray(),
                HttpStatusCode.Forbidden,
                headersOf("Cf-Mitigated", "challenge"),
            )
        })
        val extension =
            PaperbackExtension("WebtoonXYZ", webtoonXyzBundle, ApplicationHost(http = challengedSite))
        assertFailsWith<CloudflareChallengeException> { extension.search("solo") }
        Unit
    }
}
