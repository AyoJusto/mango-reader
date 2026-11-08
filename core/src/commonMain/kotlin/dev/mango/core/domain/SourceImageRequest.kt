package dev.mango.core.domain

/** One image fetch handed to [MangaSource.prepareImageRequests]; named to avoid coil3's ImageRequest. */
data class SourceImageRequest(val url: String, val headers: Map<String, String>)
