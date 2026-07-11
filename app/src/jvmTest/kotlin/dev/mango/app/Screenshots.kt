package dev.mango.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.skia.EncodedImageFormat

/**
 * Offscreen screenshot harness (M3 loop): renders a composable deterministically to
 * build/screenshots/<name>.png for visual review. Never asserted byte-exact — these are
 * review artifacts, not golden files.
 */
object Screenshots {
    fun render(name: String, width: Int = 1280, height: Int = 800, content: @Composable () -> Unit): Path {
        val scene = ImageComposeScene(width, height, Density(1f), content = content)
        val png = try {
            checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG)) {
                "PNG encoding produced no data for $name"
            }.bytes
        } finally {
            scene.close()
        }
        val dir = Paths.get("build", "screenshots")
        Files.createDirectories(dir)
        val file = dir.resolve("$name.png")
        Files.write(file, png)
        return file
    }
}
