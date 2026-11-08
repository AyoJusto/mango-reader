package dev.mango.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        assertTrue(overlay.onPointerMove(Offset(1.5f, 1.5f)))
    }
}
