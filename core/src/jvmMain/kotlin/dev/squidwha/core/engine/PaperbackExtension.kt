package dev.squidwha.core.engine

import dev.squidwha.core.domain.MangaEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * First slice of the Paperback adapter: one installed 0.9 extension, normalized to
 * domain types at this boundary. Grows into the full MangaSource implementation in M1.
 */
class PaperbackExtension(
    val sourceId: String,
    private val bundleJs: String,
    private val host: ApplicationHost,
) {
    suspend fun search(title: String, page: Int = 1): List<MangaEntry> =
        ExtensionRuntime(bundleJs, host).withExtension { handle ->
            val extension = handle.extension(sourceId)
            handle.invokeAwait(extension, "initialise")
            val result = Json.parseToJsonElement(
                handle.invokeAwaitJson(
                    extension,
                    "getSearchResults",
                    ProxyObject.fromMap(mapOf("title" to title)),
                    ProxyObject.fromMap(mapOf("page" to page)),
                    ProxyObject.fromMap(mapOf("id" to "search")),
                )
            ).jsonObject
            val items = result["items"]?.jsonArray
                ?: throw ExtensionDataException(
                    "[$sourceId] search result has no items array: ${result.keys}"
                )
            items.map { item ->
                val o = item.jsonObject
                MangaEntry(
                    sourceId = sourceId,
                    mangaId = o.requiredString("mangaId", sourceId),
                    title = o.requiredString("title", sourceId),
                    cover = (o["imageUrl"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }
}

class ExtensionDataException(message: String) : Exception(message)

/** Extension output is a trust boundary: shape drift becomes a named error, not an NPE. */
internal fun JsonObject.requiredString(field: String, sourceId: String): String {
    val value = this[field]
    if (value is JsonPrimitive && value !is JsonNull) return value.content
    throw ExtensionDataException("[$sourceId] item is missing '$field': $this")
}
