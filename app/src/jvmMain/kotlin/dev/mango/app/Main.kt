package dev.mango.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    @Suppress("UNUSED_VARIABLE")
    val graph = AppGraph()
    application {
        Window(onCloseRequest = ::exitApplication, title = "mango") {
            MangoTheme { AppShell() }
        }
    }
}
