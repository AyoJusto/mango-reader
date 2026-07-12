package dev.mango.app

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath

/**
 * Regression test: Coil's default 4096px bitmap cap silently downsampled tall webtoon strips
 * (real pages are 800 x ~14000px) before the reader upscaled them back — unreadably pixelated
 * pages.
 */
class ImageLoadingTest {
    @Test
    fun tallWebtoonStripsDecodeAtFullResolution() {
        val file = Files.createTempFile("strip", ".png")
        ImageIO.write(BufferedImage(800, 9000, BufferedImage.TYPE_INT_RGB), "png", file.toFile())

        val loader = coil3.ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val result = runBlocking {
            loader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(file.toOkioPath())
                    .size(coil3.size.Size.ORIGINAL)
                    // the reader's per-request config (see DefaultReaderPage)
                    .maxBitmapSize(WebtoonMaxBitmapSize)
                    .build()
            )
        }

        val success = assertIs<SuccessResult>(result)
        assertEquals(9000, success.image.height, "tall strip was downsampled — maxBitmapSize regressed")
        assertEquals(800, success.image.width)
    }
}
