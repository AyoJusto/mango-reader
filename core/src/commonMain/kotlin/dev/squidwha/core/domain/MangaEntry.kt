package dev.squidwha.core.domain

/** One search/browse result, normalized at the engine boundary. No infrastructure here. */
data class MangaEntry(
    val sourceId: String,
    val mangaId: String,
    val title: String,
    val cover: String? = null,
)
