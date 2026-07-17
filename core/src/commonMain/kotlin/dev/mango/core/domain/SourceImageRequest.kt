package dev.mango.core.domain

/**
 * One image fetch (a reader page or a download) as handed to [MangaSource.prepareImageRequests]
 * for source-specific preparation — an extension interceptor's URL signing or auth headers —
 * before [SourceHeaderPolicy]'s wire-boundary merge runs on top of the result. Named to avoid
 * colliding with coil3's ImageRequest, which the UI layer imports separately.
 */
data class SourceImageRequest(val url: String, val headers: Map<String, String>)
