package dev.mango.core.domain

data class MangaEntry(
    val sourceId: String,
    val mangaId: String,
    val title: String,
    val cover: String? = null,
)
