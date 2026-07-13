package dev.mango.app

import coil3.size.Size

/**
 * Webtoon strips are routinely 10k+ px tall — real sources serve segments up to ~29k px
 * (800 x 24000-28600 observed in the wild). Coil scales PROPORTIONALLY to fit this cap, so
 * any height ceiling below a page's real height shrinks its width too; the reader then
 * upscales back to strip width and the page renders blurry. The height cap is therefore the
 * JPEG format's own maximum (65500 lines), which no real page exceeds — decode never costs
 * width.
 *
 * ponytail: bitmaps taller than the GPU's max texture (commonly 16384) rely on Skia's
 * large-image drawing path; if very tall pages ever render blank or artifacted, the upgrade
 * path is tiling tall pages into stacked sub-16384 slices.
 *
 * Applied per-request in the reader: the loader-level maxBitmapSize extra does NOT
 * propagate to decode options in coil 3.5.0 (verified empirically by ImageLoadingTest).
 */
val WebtoonMaxBitmapSize = Size(8_192, 65_536)
