package dev.mango.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regenerates the app icon rasters from the vector source of truth, design/icon/mango.svg:
 * a PNG ladder plus a multi-size mango.ico, all under build/icon/. When the mark changes,
 * copy build/icon/mango.ico over app/icons/mango.ico (the packaged Windows icon) and the
 * SVG over app/src/jvmMain/composeResources/drawable/mango_icon.svg (the live window icon) —
 * kept as a test so regeneration needs no tooling beyond the suite itself.
 */
class IconRenderTest {
    private val icoSizes = listOf(16, 24, 32, 48, 64, 128, 256)

    @Test
    fun renderIconLadderAndIco() {
        val svg = Paths.get("..", "design", "icon", "mango.svg")
        val painter = Files.newInputStream(svg).use { loadSvgPainter(it, Density(1f)) }
        val outDir = Paths.get("build", "icon")
        Files.createDirectories(outDir)
        for (size in icoSizes + 512) {
            val file = Screenshots.render("../icon/mango-$size", width = size, height = size) {
                Image(painter = painter, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            assertTrue(Files.size(file) > 0)
        }

        // ICO container: ICONDIR header, one ICONDIRENTRY per size, then the PNG payloads
        // verbatim (PNG-compressed entries are valid for Vista+). All fields little-endian.
        val pngs = icoSizes.map { Files.readAllBytes(outDir.resolve("mango-$it.png")) }
        val header = ByteBuffer.allocate(6 + 16 * icoSizes.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0)
        header.putShort(1)
        header.putShort(icoSizes.size.toShort())
        var offset = 6 + 16 * icoSizes.size
        icoSizes.forEachIndexed { index, size ->
            val dimension = if (size == 256) 0 else size
            header.put(dimension.toByte())
            header.put(dimension.toByte())
            header.put(0)
            header.put(0)
            header.putShort(1)
            header.putShort(32)
            header.putInt(pngs[index].size)
            header.putInt(offset)
            offset += pngs[index].size
        }
        val ico = outDir.resolve("mango.ico")
        Files.newOutputStream(ico).use { out ->
            out.write(header.array())
            pngs.forEach(out::write)
        }
        assertTrue(Files.size(ico) > 0)
    }
}
