package dev.mango.app

import coil3.size.Size

// Bitmaps taller than the GPU's max texture size (about 16384px) won't draw.
// Per-request only: loader-level maxBitmapSize doesn't reach decode options in coil 3.5.0.
/** Height cap is JPEG's max (65500) so Coil never shrinks the width of tall pages. */
val WebtoonMaxBitmapSize = Size(8_192, 65_536)
