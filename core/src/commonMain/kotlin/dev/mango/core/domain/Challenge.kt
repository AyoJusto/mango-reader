package dev.mango.core.domain

/** A site's anti-bot challenge that only a real browser can pass; the embedded-browser flow consumes [url]. */
class ChallengeRequiredException(
    val sourceId: String?,
    val url: String?,
) : Exception("challenge required${url?.let { " for $it" } ?: ""}")
