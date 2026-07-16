package dev.mango.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.mango.core.engine.DefaultSourceHeaderPolicy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test

/** [rememberCoverRequest]'s two branches: a plain request with no policy, a header-merged one with one. */
class KitCoverRequestTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun noPolicyProducesAPlainRequestWithNoHeaders() {
        var request: ImageRequest? = null
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                val result = rememberCoverRequest("src", "https://example.com/cover.jpg")
                SideEffect { request = result }
            }
        }
        rule.waitForIdle()

        assertNull(request?.httpHeaders?.get("User-Agent"))
    }

    @Test
    fun aProvidedPolicyEventuallyProducesARequestCarryingItsMergedHeaders() {
        val policy = DefaultSourceHeaderPolicy(cookieStoreFor = { NoOpCookieStore() }, userAgentFor = { "Pinned/1.0" })
        var request: ImageRequest? = null
        rule.setContent {
            ProvideMangoTheme(MangoDark) {
                CompositionLocalProvider(LocalSourceHeaderPolicy provides policy) {
                    val result = rememberCoverRequest("src", "https://example.com/cover.jpg")
                    SideEffect { request = result }
                }
            }
        }
        rule.waitForIdle()

        assertEquals("Pinned/1.0", request?.httpHeaders?.get("User-Agent"))
    }
}
