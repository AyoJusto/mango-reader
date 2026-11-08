package dev.mango.core.domain

/** A source the registry offers for install — not yet pinned to a bundle file (see [SourceInfo]). */
data class AvailableSource(
    val sourceId: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val language: String? = null,
)

interface ExtensionRepo {
    suspend fun available(): List<AvailableSource>
    suspend fun install(source: AvailableSource)
}
