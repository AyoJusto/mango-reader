package dev.squidwha.core.engine

import dev.squidwha.core.domain.Chapter
import dev.squidwha.core.domain.MangaDetails
import dev.squidwha.core.domain.MangaEntry
import dev.squidwha.core.domain.MangaSource
import dev.squidwha.core.domain.MangaStatus
import dev.squidwha.core.domain.Page
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * First slice of the Paperback adapter: one installed 0.9 extension, normalized to
 * domain types at this boundary. Grows into the full MangaSource implementation in M1.
 */
class PaperbackExtension(
    override val sourceId: String,
    private val bundleJs: String,
    private val host: ApplicationHost,
) : MangaSource {
    override suspend fun search(query: String, page: Int): List<MangaEntry> =
        ExtensionRuntime(bundleJs, host).withExtension { handle ->
            val extension = handle.extension(sourceId)
            handle.invokeAwait(extension, "initialise")
            val result = Json.parseToJsonElement(
                handle.invokeAwaitJson(
                    extension,
                    "getSearchResults",
                    ProxyObject.fromMap(mapOf("title" to query)),
                    ProxyObject.fromMap(mapOf("page" to page)),
                    ProxyObject.fromMap(mapOf("id" to "search")),
                )
            ).jsonObject
            val items = result["items"] as? JsonArray
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

    override suspend fun getDetails(mangaId: String): MangaDetails =
        ExtensionRuntime(bundleJs, host).withExtension { handle ->
            val extension = handle.extension(sourceId)
            handle.invokeAwait(extension, "initialise")
            // 0.9 getMangaDetails takes the raw mangaId string (confirmed in the bundle)
            val result = Json.parseToJsonElement(
                handle.invokeAwaitJson(extension, "getMangaDetails", mangaId)
            ).jsonObject
            val info = result["mangaInfo"] as? JsonObject
                ?: throw ExtensionDataException(
                    "[$sourceId] details result has no mangaInfo object: ${result.keys}"
                )
            MangaDetails(
                entry = MangaEntry(
                    sourceId = sourceId,
                    mangaId = result.requiredString("mangaId", sourceId),
                    title = info.requiredString("primaryTitle", sourceId),
                    cover = (info["thumbnailUrl"] as? JsonPrimitive)?.contentOrNull,
                ),
                // Paperback's author field is a single display string; sources join
                // multiple names with ", "
                authors = (info["author"] as? JsonPrimitive)?.contentOrNull
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    .orEmpty(),
                description = (info["synopsis"] as? JsonPrimitive)?.contentOrNull,
                status = statusOf((info["status"] as? JsonPrimitive)?.contentOrNull),
                // tags are best-effort: malformed elements are skipped, never fatal
                tags = (info["tagGroups"] as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .flatMap { group -> (group["tags"] as? JsonArray).orEmpty() }
                    .mapNotNull { tag -> ((tag as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull },
            )
        }

    override suspend fun getChapters(mangaId: String): List<Chapter> =
        ExtensionRuntime(bundleJs, host).withExtension { handle ->
            val extension = handle.extension(sourceId)
            handle.invokeAwait(extension, "initialise")
            val result = Json.parseToJsonElement(
                handle.invokeAwaitJson(
                    extension,
                    "getChapters",
                    ProxyObject.fromMap(mapOf("mangaId" to mangaId)),
                )
            ).jsonArray
            result.map { item ->
                val o = item.jsonObject
                Chapter(
                    chapterId = o.requiredString("chapterId", sourceId),
                    number = (o["chapNum"] as? JsonPrimitive)?.doubleOrNull
                        ?: throw ExtensionDataException(
                            "[$sourceId] chapter is missing numeric 'chapNum': $o"
                        ),
                    title = (o["title"] as? JsonPrimitive)?.contentOrNull,
                    language = (o["langCode"] as? JsonPrimitive)?.contentOrNull,
                    publishedAt = (o["publishDate"] as? JsonPrimitive)?.contentOrNull
                        ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                )
            }
        }

    override suspend fun getPages(mangaId: String, chapterId: String): List<Page> =
        ExtensionRuntime(bundleJs, host).withExtension { handle ->
            val extension = handle.extension(sourceId)
            handle.invokeAwait(extension, "initialise")
            val result = Json.parseToJsonElement(
                handle.invokeAwaitJson(
                    extension,
                    "getChapterDetails",
                    ProxyObject.fromMap(
                        mapOf(
                            "chapterId" to chapterId,
                            "sourceManga" to ProxyObject.fromMap(mapOf("mangaId" to mangaId)),
                        )
                    ),
                )
            ).jsonObject
            val pages = result["pages"] as? JsonArray
                ?: throw ExtensionDataException(
                    "[$sourceId] chapter details has no pages array: ${result.keys}"
                )
            pages.mapIndexed { index, page ->
                // 0.9 ChapterDetails.pages is an array of URL strings
                val url = (page as? JsonPrimitive)?.contentOrNull
                    ?: throw ExtensionDataException("[$sourceId] page $index has no url: $page")
                Page(index = index, url = url)
            }
        }

    private fun statusOf(raw: String?): MangaStatus =
        MangaStatus.entries.firstOrNull { it.name == raw?.uppercase() } ?: MangaStatus.UNKNOWN
}

class ExtensionDataException(message: String) : Exception(message)

/** Extension output is a trust boundary: shape drift becomes a named error, not an NPE. */
internal fun JsonObject.requiredString(field: String, sourceId: String): String {
    val value = this[field]
    if (value is JsonPrimitive && value !is JsonNull) return value.content
    throw ExtensionDataException("[$sourceId] item is missing '$field': $this")
}
