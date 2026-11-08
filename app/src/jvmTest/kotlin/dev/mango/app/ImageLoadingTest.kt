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
                    .maxBitmapSize(WebtoonMaxBitmapSize)
                    .build()
            )
        }

        val success = assertIs<SuccessResult>(result)
        assertEquals(9000, success.image.height, "tall strip was downsampled — maxBitmapSize regressed")
        assertEquals(800, success.image.width)
    }

    @Test
    fun extremelyTallStripsKeepTheirNativeWidth() {
        val file = Files.createTempFile("strip-tall", ".png")
        ImageIO.write(BufferedImage(800, 27_582, BufferedImage.TYPE_INT_RGB), "png", file.toFile())

        val loader = coil3.ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val result = runBlocking {
            loader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(file.toOkioPath())
                    .size(coil3.size.Size.ORIGINAL)
                    .maxBitmapSize(WebtoonMaxBitmapSize)
                    .build()
            )
        }

        val success = assertIs<SuccessResult>(result)
        assertEquals(800, success.image.width, "tall strip lost width at decode — maxBitmapSize height cap too low")
        assertEquals(27_582, success.image.height)
    }
}
