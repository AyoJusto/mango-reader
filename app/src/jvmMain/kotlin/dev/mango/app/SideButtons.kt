package dev.mango.app

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

private val getAsyncKeyState by lazy {
    user32Function("GetAsyncKeyState", FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT))
}

private fun held(virtualKey: Int): Boolean {
    val state = getAsyncKeyState.invoke(virtualKey) as Short
    return (state.toInt() and KEY_HELD) != 0
}

/**
 * Which mouse side button is held right now. Nucleus reports every extra mouse button as a
 * primary press, so a press handler calls this to learn what the user actually pressed. A held
 * left button wins: the press is then a real click.
 */
// ponytail: polls the Win32 key state at press time, so Windows only and blind to a side button
// released before the press is handled; drop it once Nucleus maps its OTHER mouse button to
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
