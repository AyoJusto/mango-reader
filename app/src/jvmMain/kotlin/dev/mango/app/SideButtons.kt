package dev.mango.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

enum class SideButton { None, Back, Forward }

private const val VK_LBUTTON = 0x01
private const val VK_XBUTTON1 = 0x05
private const val VK_XBUTTON2 = 0x06
private const val KEY_HELD = 0x8000

private val logger = Logger.getLogger("SideButtons")
private val warned = AtomicBoolean(false)

// Not GetAsyncKeyState: mouse software taps side buttons, so the live state already reads released.
private val getKeyState by lazy {
    user32Function("GetKeyState", FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT))
}

private fun held(virtualKey: Int): Boolean {
    val state = getKeyState.invoke(virtualKey) as Short
    return (state.toInt() and KEY_HELD) != 0
}

/**
 * Which mouse side button is held right now. Nucleus reports every extra mouse button as a
 * primary press, so a press handler calls this to learn what the user actually pressed. A held
 * left button wins: the press is then a real click.
 */
// ponytail: reads the Win32 key state at press time, so Windows only and only correct on the
// window thread that received the press; drop it once Nucleus maps its OTHER mouse button to
// PointerButton.Back/Forward.
fun pressedSideButton(): SideButton =
    try {
        when {
            held(VK_LBUTTON) -> SideButton.None
            held(VK_XBUTTON1) -> SideButton.Back
            held(VK_XBUTTON2) -> SideButton.Forward
            else -> SideButton.None
        }
    } catch (failure: Exception) {
        if (warned.compareAndSet(false, true)) {
            logger.log(Level.WARNING, "Could not read the mouse side buttons", failure)
        }
        SideButton.None
    }

/** How [sideButtons] learns which side button is held; tests provide a canned answer. */
val LocalSideButton = staticCompositionLocalOf<() -> SideButton> { ::pressedSideButton }

/**
 * Turns a mouse side-button press anywhere inside this element into [onBack] for Back and nothing
 * for Forward. The press is consumed on the Initial pass, so no clickable inside treats it as the
 * primary click Nucleus reports it as. Popups and dialogs are separate scene layers that a
 * handler on the window content never sees, so each one applies its own.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.sideButtons(onBack: () -> Unit): Modifier {
    val sideButton = LocalSideButton.current
    return onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        when (sideButton()) {
            SideButton.Back -> {
                event.changes.forEach { it.consume() }
                onBack()
            }

            SideButton.Forward -> event.changes.forEach { it.consume() }
            SideButton.None -> Unit
        }
    }
}
