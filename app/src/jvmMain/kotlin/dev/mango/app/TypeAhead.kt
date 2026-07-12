package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keyboard type-ahead over a fixed option list: printable characters accumulate into a prefix
 * [buffer] that jumps [activeIndex] to the first case-insensitive match; arrow steps move it one
 * row, bounded, without wrapping. A keystroke that matches nothing keeps the buffer (the user may
 * still be mid-prefix) but leaves the position alone.
 *
 * Deliberately clock-free: the host owns the idle timeout and calls [clearBuffer], so this logic
 * is unit-testable without a UI harness. State is snapshot-backed so a composable host recomposes
 * on changes.
 */
class TypeAheadState(private val options: List<String>, initialIndex: Int) {
    var activeIndex by mutableIntStateOf(initialIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
        private set
    var buffer by mutableStateOf("")
        private set

    fun onChar(char: Char) {
        buffer += char
        val match = options.indexOfFirst { it.startsWith(buffer, ignoreCase = true) }
        if (match >= 0) activeIndex = match
    }

    fun onArrowDown() {
        if (activeIndex < options.size - 1) activeIndex++
    }

    fun onArrowUp() {
        if (activeIndex > 0) activeIndex--
    }

    fun clearBuffer() {
        buffer = ""
    }
}
