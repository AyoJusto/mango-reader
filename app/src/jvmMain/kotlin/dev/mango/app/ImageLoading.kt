package dev.mango.app

import coil3.size.Size

/**
 * Webtoon strips are routinely 10k+ px tall. Coil's default 4096px bitmap cap downsampled
 * them to ~230px wide before the reader upscaled them back — a "pixelated pages" bug. Height
 * 16384 matches the common GPU max texture size; the rare taller strip still gets downsampled
 * proportionally — splitting tall images into segments is the upgrade path.
 *
 * Applied per-request in the reader: the loader-level maxBitmapSize extra does NOT
 * propagate to decode options in coil 3.5.0 (verified empirically by ImageLoadingTest).
 */
val WebtoonMaxBitmapSize = Size(8_192, 16_384)
