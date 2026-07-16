package dev.mango.core.engine

import dev.mango.core.domain.SourceImageRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * [PaperbackExtension.prepareImageRequests] through the synthetic bundle's interceptor: a batch
 * runs through one fresh context (initialise + the interceptor chain paid once, not per item),
 * in order, with a per-item degrade on interceptor failure. No HTTP client is needed — the chain
 * never dispatches.
 */
class PrepareImageRequestsTest {
    @Test
    fun batchRunsThroughTheInterceptorInOrderAndDegradesAThrowingItem() = runBlocking {
        val extension = PaperbackExtension("Synthetic", syntheticBundle, ApplicationHost())
        val requests = listOf(
            SourceImageRequest(url = "https://synthetic.example/img/page1.jpg", headers = mapOf("Referer" to "r1")),
            SourceImageRequest(
                url = "https://synthetic.example/img/throw-me/page2.jpg",
                headers = mapOf("Referer" to "r2"),
            ),
            SourceImageRequest(url = "https://synthetic.example/img/page3.jpg", headers = mapOf("Referer" to "r3")),
        )

        val result = extension.prepareImageRequests(requests)

        assertEquals(3, result.size)
        assertEquals("https://synthetic.example/signed/page1.jpg", result[0].url)
        assertEquals("1", result[0].headers["x-synthetic-intercepted"])
        assertEquals("r1", result[0].headers["referer"])

        assertEquals(requests[1], result[1], "expected the throwing item to degrade to its own input")

        assertEquals("https://synthetic.example/signed/page3.jpg", result[2].url)
        assertEquals("1", result[2].headers["x-synthetic-intercepted"])
        assertEquals("r3", result[2].headers["referer"])
    }
}
