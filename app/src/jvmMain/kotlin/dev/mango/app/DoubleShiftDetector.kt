package dev.mango.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

private const val DOUBLE_SHIFT_WINDOW_MS = 500L

/** Needs a keyup between the downs, so an auto-repeating held Shift never triggers. */
class DoubleShiftDetector {
    private var pendingDownAt: Long? = null

    private var armedAt: Long? = null

    /** Returns true exactly on the keydown that completes a double-shift. */
    fun onKeyEvent(key: Key, type: KeyEventType, nowMillis: Long): Boolean {
        if (key != Key.ShiftLeft && key != Key.ShiftRight) {
            if (type == KeyEventType.KeyDown) {
                pendingDownAt = null
                armedAt = null
            }
            return false
        }
        return when (type) {
            KeyEventType.KeyDown -> {
                val arm = armedAt
                if (arm != null && nowMillis - arm <= DOUBLE_SHIFT_WINDOW_MS) {
                    pendingDownAt = null
                    armedAt = null
                    true
                } else {
                    armedAt = null
                    pendingDownAt = nowMillis
                    false
                }
            }

            KeyEventType.KeyUp -> {
                pendingDownAt?.let { armedAt = it }
                pendingDownAt = null
                false
            }

            else -> false
        }
    }
}
