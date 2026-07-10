package dev.squidwha.core.engine

import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineSmokeTest {
    @Test
    fun quickJsEvaluatesArithmetic() = runBlocking {
        val result = quickJs { evaluate<Int>("1 + 2") }
        assertEquals(3, result)
    }
}
