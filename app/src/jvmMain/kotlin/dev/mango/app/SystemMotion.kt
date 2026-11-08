package dev.mango.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.util.logging.Level
import java.util.logging.Logger

private const val SPI_GETCLIENTAREAANIMATION = 0x1042

private val logger = Logger.getLogger("SystemMotion")

private val systemParametersInfo by lazy {
    user32Function(
        "SystemParametersInfoW",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
}

fun windowsAnimationsOn(): Boolean =
    try {
        Arena.ofConfined().use { arena ->
            val flag = arena.allocate(ValueLayout.JAVA_INT)
            val succeeded = systemParametersInfo.invoke(SPI_GETCLIENTAREAANIMATION, 0, flag, 0) as Int
            succeeded == 0 || flag.get(ValueLayout.JAVA_INT, 0) != 0
        }
    } catch (failure: Exception) {
        logger.log(Level.WARNING, "Could not read the Windows animation setting", failure)
        true
    }

@Composable
fun rememberWindowsAnimationsOn(): Boolean {
    var on by remember { mutableStateOf(windowsAnimationsOn()) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) on = windowsAnimationsOn() }
    return on
}
