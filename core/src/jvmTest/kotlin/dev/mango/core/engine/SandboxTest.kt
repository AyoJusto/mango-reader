package dev.mango.core.engine

import org.graalvm.polyglot.PolyglotException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Pins the sandbox: the production context builders must deny every capability beyond pure JS. */
class SandboxTest {
    private fun probe(js: String) {
        newExtensionContext().use { context ->
            assertFailsWith<PolyglotException>("sandbox must block: $js") {
                context.eval("js", js)
            }
        }
        newExtensionEngine().use { engine ->
            newExtensionContext(engine).use { context ->
                assertFailsWith<PolyglotException>("sandbox must block (shared engine): $js") {
                    context.eval("js", js)
                }
            }
        }
    }

    @Test
    fun hostClassLookupIsDenied() = probe("Java.type('java.lang.Runtime')")

    @Test
    fun processAccessIsDenied() = probe("new (Java.type('java.lang.ProcessBuilder'))(['calc'])")

    @Test
    fun fileLoadingIsDenied() = probe("load('C:/Windows/win.ini')")
}
