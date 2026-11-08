package dev.mango.app

import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainDispatcherTest {
    @Test
    fun mainDispatcherIsProvided() = runBlocking {
        withContext(Dispatchers.Main) {}
    }
}
