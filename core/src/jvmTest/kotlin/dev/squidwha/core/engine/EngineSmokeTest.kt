package dev.squidwha.core.engine

import org.graalvm.polyglot.Context
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineSmokeTest {
    @Test
    fun graalJsEvaluatesArithmetic() {
        Context.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build().use { context ->
                assertEquals(3, context.eval("js", "1 + 2").asInt())
            }
    }
}
