package dev.mango.core.domain

/**
 * A source's site answered with an anti-bot challenge (Cloudflare) that only a real browser
 * can pass. Domain-level so screens can catch it through the repository ports without
 * touching engine types; M4.3b's embedded-browser flow consumes [url].
 */
class ChallengeRequiredException(
    val sourceId: String?,
    val url: String?,
) : Exception("challenge required${url?.let { " for $it" } ?: ""}")
