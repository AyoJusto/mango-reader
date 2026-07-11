package dev.mango.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for [DoubleShiftDetector] — pure class, fake millis, no Compose runtime needed. */
class DoubleShiftDetectorTest {
    private val detector = DoubleShiftDetector()

    private fun down(key: Key, at: Long) = detector.onKeyEvent(key, KeyEventType.KeyDown, at)
    private fun up(key: Key, at: Long) = detector.onKeyEvent(key, KeyEventType.KeyUp, at)

    @Test
    fun downUpDownWithin500msTriggers() {
        assertFalse(down(Key.ShiftLeft, 0))
        assertFalse(up(Key.ShiftLeft, 50))
        assertTrue(down(Key.ShiftLeft, 400))
    }

    @Test
    fun downUpDownMoreThan500msApartDoesNotTrigger() {
        assertFalse(down(Key.ShiftLeft, 0))
        assertFalse(up(Key.ShiftLeft, 50))
        // window counts from the FIRST keydown, so 501ms after it is out
        assertFalse(down(Key.ShiftLeft, 501))
    }

    @Test
    fun anotherKeyBetweenTheTwoShiftsResetsTheChord() {
        assertFalse(down(Key.ShiftLeft, 0))
        assertFalse(up(Key.ShiftLeft, 50))
        assertFalse(down(Key.A, 100))
        assertFalse(down(Key.ShiftLeft, 200))
    }

    @Test
    fun afterATriggerTheNextShiftPairTriggersAgain() {
        assertFalse(down(Key.ShiftLeft, 0))
        assertFalse(up(Key.ShiftLeft, 50))
        assertTrue(down(Key.ShiftLeft, 200))
        assertFalse(up(Key.ShiftLeft, 250))

        assertFalse(down(Key.ShiftLeft, 1000))
        assertFalse(up(Key.ShiftLeft, 1050))
        assertTrue(down(Key.ShiftLeft, 1200))
    }

    @Test
    fun heldShiftAutoRepeatNeverTriggers() {
        // the OS repeats a held key as keydowns with NO keyup between them
        assertFalse(down(Key.ShiftLeft, 0))
        assertFalse(down(Key.ShiftLeft, 30))
        assertFalse(down(Key.ShiftLeft, 60))
        assertFalse(down(Key.ShiftLeft, 90))
    }
}
