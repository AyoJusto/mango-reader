package dev.mango.core.domain

/** One home/discover shelf, normalized at the engine boundary. No layout/type here — the
 * UI decides how to render a shelf; only the source-provided id, title, and items matter. */
data class HomeSection(val id: String, val title: String, val items: List<MangaEntry>)
