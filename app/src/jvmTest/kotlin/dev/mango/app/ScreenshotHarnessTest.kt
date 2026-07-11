package dev.mango.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ScreenshotHarnessTest {
    @Test
    fun rendersAComposableToAPngOnDisk() {
        val file = Screenshots.render("harness-gate", width = 400, height = 300) {
            MaterialTheme { Text("mango") }
        }
        assertTrue(Files.size(file) > 0, "expected a non-empty PNG at $file")
    }
}
