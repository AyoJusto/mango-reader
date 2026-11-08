package dev.mango.core.domain

/** One home/discover shelf; the UI decides how to render it. */
data class HomeSection(val id: String, val title: String, val items: List<MangaEntry>)
