package dev.mango.app

import kotlin.test.Test
import kotlin.test.assertEquals

class TypeAheadStateTest {
    private val fonts = listOf("Arial", "Cambria", "Candara", "Georgia", "Segoe UI", "Tahoma")

    @Test
    fun typingJumpsToFirstPrefixMatchCaseInsensitively() {
        val state = TypeAheadState(fonts, initialIndex = 0)
        state.onChar('g')
        assertEquals(3, state.activeIndex)
    }

    @Test
    fun bufferAccumulatesToDisambiguateSharedPrefixes() {
        val state = TypeAheadState(fonts, initialIndex = 0)
        state.onChar('c')
        assertEquals(1, state.activeIndex)
        state.onChar('a')
        assertEquals(1, state.activeIndex)
        state.onChar('n')
        assertEquals(2, state.activeIndex)
    }

    @Test
    fun noMatchKeepsPositionAndBuffer() {
        val state = TypeAheadState(fonts, initialIndex = 3)
        state.onChar('z')
        assertEquals(3, state.activeIndex)
        assertEquals("z", state.buffer)
        state.onChar('g')
        assertEquals(3, state.activeIndex)
        assertEquals("zg", state.buffer)
    }

    @Test
    fun clearBufferStartsANewPrefix() {
        val state = TypeAheadState(fonts, initialIndex = 0)
        state.onChar('t')
        assertEquals(5, state.activeIndex)
        state.clearBuffer()
        state.onChar('a')
        assertEquals(0, state.activeIndex)
    }

    @Test
    fun arrowsAreBoundedWithoutWrapping() {
        val state = TypeAheadState(fonts, initialIndex = 5)
        state.onArrowDown()
        assertEquals(5, state.activeIndex)
        state.onArrowUp()
        assertEquals(4, state.activeIndex)
        val top = TypeAheadState(fonts, initialIndex = 0)
        top.onArrowUp()
        assertEquals(0, top.activeIndex)
        top.onArrowDown()
        assertEquals(1, top.activeIndex)
    }

    @Test
    fun initialIndexIsCoercedIntoBounds() {
        assertEquals(0, TypeAheadState(fonts, initialIndex = -1).activeIndex)
        assertEquals(5, TypeAheadState(fonts, initialIndex = 99).activeIndex)
        assertEquals(0, TypeAheadState(emptyList(), initialIndex = 3).activeIndex)
    }

    @Test
    fun spacesParticipateInPrefixMatching() {
        val state = TypeAheadState(fonts, initialIndex = 0)
        "segoe u".forEach(state::onChar)
        assertEquals(4, state.activeIndex)
        assertEquals("segoe u", state.buffer)
    }
}
