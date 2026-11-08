package dev.mango.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class NavigatorTest {
    private val detailsA = Screen.Details("FlameComics", "manga-a")
    private val detailsB = Screen.Details("FlameComics", "manga-b")

    @Test
    fun startsOnLibraryAndCannotGoBack() {
        val nav = Navigator()

        assertEquals(Screen.Library, nav.current.screen)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun detailsPushesAndBackReturnsToTheScreenItCameFrom() {
        val nav = Navigator()
        nav.go(Screen.Search)
        nav.go(detailsA)

        assertEquals(Move.Push, nav.lastMove)
        assertTrue(nav.canGoBack)

        assertTrue(nav.back())
        assertEquals(Screen.Search, nav.current.screen)
        assertEquals(Move.Pop, nav.lastMove)
    }

    @Test
    fun topLevelSwitchResetsTheStack() {
        val nav = Navigator()
        nav.go(detailsA)
        nav.go(Screen.Settings)

        assertEquals(Screen.Settings, nav.current.screen)
        assertFalse(nav.canGoBack)
        assertEquals(Move.Switch, nav.lastMove)
        assertEquals(listOf(nav.current.id), nav.liveIds)
    }

    @Test
    fun goingToTheCurrentScreenDoesNothing() {
        val nav = Navigator()
        nav.go(Screen.Search)
        nav.go(detailsA)
        nav.back()
        val before = nav.current

        nav.go(Screen.Search)

        assertEquals(before.id, nav.current.id)
        assertEquals(Move.Pop, nav.lastMove)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun detailsOnDetailsPushes() {
        val nav = Navigator()
        nav.go(detailsA)
        nav.go(detailsB)

        assertTrue(nav.back())
        assertEquals(detailsA, nav.current.screen)
        assertTrue(nav.back())
        assertEquals(Screen.Library, nav.current.screen)
    }

    @Test
    fun backAtRootReturnsFalse() {
        val nav = Navigator()
        val root = nav.current

        assertFalse(nav.back())
        assertEquals(root, nav.current)
    }

    @Test
    fun replaceCurrentKeepsTheId() {
        val nav = Navigator()
        nav.go(detailsA.copy(autoContinue = true))
        val id = nav.current.id

        nav.replaceCurrent(detailsA)

        assertEquals(id, nav.current.id)
        assertEquals(detailsA, nav.current.screen)
    }

    @Test
    fun everyEntryGetsAFreshId() {
        val nav = Navigator()
        val libraryId = nav.current.id
        nav.go(detailsA)

        assertNotEquals(libraryId, nav.current.id)
        assertEquals(listOf(libraryId, nav.current.id), nav.liveIds)
    }
}
