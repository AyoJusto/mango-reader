package dev.mango.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionTest {
    @Test
    fun defaultMotionKeepsTheLookbookTimings() {
        val m = MangoMotion()
        assertEquals(120, m.hoverMs); assertEquals(240, m.sidebarOpenMs); assertEquals(1.03f, m.coverHoverScale)
        assertTrue(m.slides)
        assertTrue(m.pillPulseMs <= 320)
    }

    @Test
    fun reducedMotionTurnsEveryAnimationIntoA120msFadeAndRemovesMovement() {
        val m = MangoMotion(reduced = true)
        listOf(
            m.hoverMs, m.pressMs, m.sidebarOpenMs, m.sidebarCloseMs, m.paletteOpenMs, m.paletteBackdropMs,
            m.readerOverlayInMs, m.readerOverlayOutMs, m.readerPageCrossfadeMs, m.coverHoverMs, m.progressBarMs,
            m.bannerInMs, m.screenMs, m.viewChangeMs, m.pillPulseMs
        ).forEach { assertEquals(120, it) }
        assertEquals(0, m.sidebarStaggerMs)
        assertEquals(1f, m.pressScale); assertEquals(1f, m.coverHoverScale)
        assertEquals(0.dp, m.screenSlide); assertEquals(0.dp, m.viewRise)
        assertFalse(m.slides)
        assertEquals(1500, m.readerIdleMs)
    }
}
