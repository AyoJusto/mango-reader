package dev.mango.core.engine

import dev.mango.core.domain.ChallengeRequiredException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ChallengeDetectionTest {
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
        assertFailsWith<ChallengeRequiredException> { extension.search("solo") }
        Unit
    }

    @Test
    fun synthetic403WithRecaptchaBodySurfacesAsCloudflareChallenge() = runBlocking {
        val challengedSite = HttpClient(MockEngine {
            respond(
                "<html>please solve this recaptcha to continue</html>".encodeToByteArray(),
                HttpStatusCode.Forbidden,
            )
        })
        val extension = PaperbackExtension("Synthetic", syntheticBundle, ApplicationHost(http = challengedSite))
        assertFailsWith<ChallengeRequiredException> { extension.search("solo") }
        Unit
    }

    @Test
    fun syntheticWireCasedChallengeHeaderIsStillDetected() = runBlocking {
        val challengedSite = HttpClient(MockEngine {
            respond(
                "<html>checking your browser</html>".encodeToByteArray(),
                HttpStatusCode.Forbidden,
                headersOf("Cf-Mitigated", "challenge"),
            )
        })
        val extension = PaperbackExtension("Synthetic", syntheticBundle, ApplicationHost(http = challengedSite))
        assertFailsWith<ChallengeRequiredException> { extension.search("solo") }
        Unit
    }
}
