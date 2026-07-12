package dev.mango.core.engine

import org.graalvm.polyglot.PolyglotException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the sandbox: the production context builder must deny every capability beyond
 * pure JS execution. If a future change to [newExtensionContext] or [newExtensionEngine]
 * opens host access, these fail. Probes run through the exact builders production uses —
 * both the standalone-context path and the shared-engine path [ExtensionRuntime] runs.
 */
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
