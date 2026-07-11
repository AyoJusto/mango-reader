package dev.mango.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/** How long the second Shift keydown has to land after the first to count as a double-tap. */
private const val DOUBLE_SHIFT_WINDOW_MS = 500L

/**
 * Detects an IntelliJ-style "double Shift" chord: Shift down, Shift up, then a second Shift
 * down within [DOUBLE_SHIFT_WINDOW_MS] of the FIRST keydown, with no other keydown in between.
 * The keyup requirement is what tells a real double-tap from one held Shift — the OS
 * auto-repeats a held key as a stream of keydowns with no keyup, which must never trigger.
 * Pure — the caller supplies the clock (testability) and feeds it every key event, downs and
 * ups (the detector filters; keyups of non-Shift keys are ignored).
 */
class DoubleShiftDetector {
    // shift is currently held (keydown seen, keyup not yet); auto-repeat keydowns keep landing here
    private var pendingDownAt: Long? = null

    // a completed down→up: the next shift keydown within the window (from the DOWN time) triggers
    private var armedAt: Long? = null

    /** Feed one key event. Returns true exactly on the keydown that completes a double-shift. */
    fun onKeyEvent(key: Key, type: KeyEventType, nowMillis: Long): Boolean {
        if (key != Key.ShiftLeft && key != Key.ShiftRight) {
            // any other KEYDOWN breaks the chord; foreign keyups (e.g. releasing a letter
            // typed before the first shift tap) are irrelevant and ignored
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
                    armedAt = null // re-arm from scratch after a trigger
                    true
                } else {
                    armedAt = null
                    pendingDownAt = nowMillis
                    false
                }
            }
            KeyEventType.KeyUp -> {
                // the release completes the first tap; the window still counts from its keydown
                pendingDownAt?.let { armedAt = it }
                pendingDownAt = null
                false
            }
            else -> false
        }
    }
}
