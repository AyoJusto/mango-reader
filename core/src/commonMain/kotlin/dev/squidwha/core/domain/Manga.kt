package dev.squidwha.core.domain

/** Full detail view of one series, normalized at the engine boundary. No infrastructure here. */
data class MangaDetails(
    val entry: MangaEntry,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val tags: List<String> = emptyList(),
)

enum class MangaStatus { ONGOING, COMPLETED, HIATUS, CANCELLED, UNKNOWN }

data class Chapter(
    val chapterId: String,
    val number: Double,
    val title: String? = null,
    val language: String? = null,
    val publishedAt: kotlin.time.Instant? = null,
)

data class Page(
    val index: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
