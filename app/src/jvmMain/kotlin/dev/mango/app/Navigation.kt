package dev.mango.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mango.core.domain.Chapter

sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data object Browse : Screen
    data object Downloads : Screen
    data object Extensions : Screen
    data object Settings : Screen
    data class Details(
        val sourceId: String,
        val mangaId: String,
        /** Must be cleared on the stack entry before the Reader is pushed, or Back bounces straight in. */
        val autoContinue: Boolean = false,
    ) : Screen

    data class Reader(
        val sourceId: String,
        val mangaId: String,
        val chapterId: String,
        // Sorted ascending at the only construction site; the reader walks it directly.
        val chapters: List<Chapter>,
    ) : Screen
}

enum class Move { Push, Pop, Switch }

data class NavEntry(val screen: Screen, val id: Long)

class Navigator {
    private var nextId = 0L
    private val stack = mutableStateListOf(NavEntry(Screen.Library, nextId++))

    val current: NavEntry get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    var lastMove: Move by mutableStateOf(Move.Switch)
        private set
    val liveIds: List<Long> get() = stack.map { it.id }

    fun go(screen: Screen) {
        if (screen == current.screen) return
        val entry = NavEntry(screen, nextId++)
        if (screen is Screen.Details || screen is Screen.Reader) {
            stack.add(entry)
            lastMove = Move.Push
        } else {
            stack.clear()
            stack.add(entry)
            lastMove = Move.Switch
        }
    }

    fun replaceCurrent(screen: Screen) {
        stack[stack.lastIndex] = current.copy(screen = screen)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        lastMove = Move.Pop
        return true
    }
}
