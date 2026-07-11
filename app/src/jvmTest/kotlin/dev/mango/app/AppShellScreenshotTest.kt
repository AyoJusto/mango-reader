package dev.mango.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class AppShellScreenshotTest {
    @Test
    fun rendersTheEmptyLibraryShell() {
        val file = Screenshots.render("app-shell", 1280, 800) {
            MangoTheme { AppShell() }
        }

        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }
}
