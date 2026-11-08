package dev.mango.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

private object Win32 {
    private val linker = Linker.nativeLinker()
    private val user32 = SymbolLookup.libraryLookup("user32", Arena.global())
    private val showCursor = linker.downcallHandle(
        user32.find("ShowCursor").get(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )

    fun showCursor(show: Boolean): Int = showCursor.invokeExact(if (show) 1 else 0) as Int
}

private val isWindows = System.getProperty("os.name").startsWith("Windows")

private fun showCursor(show: Boolean) {
    if (isWindows) Win32.showCursor(show)
}

/**
 * Hides the mouse cursor while [hidden] is true. ShowCursor is a per-thread display counter,
 * so every hide is paired with exactly one show and both run on the window's UI thread
 * (composition apply). The cursor reappears whenever it leaves the window.
 */
@Composable
fun HideCursorWhile(hidden: Boolean) {
    DisposableEffect(hidden) {
        if (hidden) showCursor(false)
        onDispose { if (hidden) showCursor(true) }
    }
}
