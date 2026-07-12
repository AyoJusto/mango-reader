package dev.mango.app

import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainDispatcherTest {
    /**
     * Dispatchers.Main on the JVM only exists when a provider module (kotlinx-coroutines-swing)
     * is on the classpath. Nothing in the UI test suite exercises it — Coil's prefetch enqueue
     * is the one production caller, and it skips test fixtures — so without this canary a
     * missing provider crashes only in a live reader session.
     */
    @Test
    fun mainDispatcherIsProvided() = runBlocking {
        withContext(Dispatchers.Main) {}
    }
}
