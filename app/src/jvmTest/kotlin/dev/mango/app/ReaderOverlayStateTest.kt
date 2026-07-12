package dev.mango.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for [ReaderOverlayState]'s move-delta gate — no Compose composition, no
 * coroutines. The idle-hide and palette-pin behavior the gate feeds is Compose-effect
 * territory, covered by [ReaderFlowTest].
 */
class ReaderOverlayStateTest {
    @Test
    fun firstSampleOnlyEstablishesABaselineAndNeverReveals() {
        val overlay = ReaderOverlayState()

        assertFalse(overlay.onPointerMove(Offset(400f, 400f)))
    }

    @Test
    fun ignoresAMoveBelowTheThreshold() {
        val overlay = ReaderOverlayState()
        overlay.onPointerMove(Offset(0f, 0f))

        val revealed = overlay.onPointerMove(Offset(1f, 0f))

        assertFalse(revealed, "expected a sub-threshold delta (Compose's synthetic hover-move noise) to not reveal")
    }

    @Test
    fun revealsOnAMoveAtOrAboveTheThreshold() {
        val overlay = ReaderOverlayState()
        overlay.onPointerMove(Offset(0f, 0f))

        assertTrue(overlay.onPointerMove(Offset(2f, 0f)))
    }

    @Test
    fun revealsOnADiagonalMoveWhoseEuclideanDistanceMeetsTheThreshold() {
        val overlay = ReaderOverlayState()
        overlay.onPointerMove(Offset(0f, 0f))

        // 1.5px per axis is under the threshold on each axis alone, but ~2.12px of distance.
        assertTrue(overlay.onPointerMove(Offset(1.5f, 1.5f)))
    }
}
