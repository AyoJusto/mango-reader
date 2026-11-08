package dev.mango.core.engine

import dev.mango.core.domain.Chapter
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaSource
import dev.mango.core.domain.MangaStatus
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceImageRequest
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

/** Paperback 0.9 adapter that normalizes extension output to [MangaSource] domain types. */
class PaperbackExtension(
    override val sourceId: String,
    bundleJs: String,
    host: ApplicationHost,
) : MangaSource {
    private val runtime = ExtensionRuntime(bundleJs, host)

    override suspend fun search(query: String, page: Int): List<MangaEntry> =
        runtime.withExtension { handle ->
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
            mangaEntriesOf(items)
        }

    override suspend fun getDetails(mangaId: String): MangaDetails =
        runtime.withExtension { handle ->
            val extension = handle.extension(sourceId)
            handle.invokeAwait(extension, "initialise")
            // 0.9 getMangaDetails takes the raw mangaId string.
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
                authors = (info["author"] as? JsonPrimitive)?.contentOrNull
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    .orEmpty(),
                description = (info["synopsis"] as? JsonPrimitive)?.contentOrNull,
                status = statusOf((info["status"] as? JsonPrimitive)?.contentOrNull),
                tags = (info["tagGroups"] as? JsonArray).orEmpty()
                    .filterIsInstance<JsonObject>()
                    .flatMap { group -> (group["tags"] as? JsonArray).orEmpty() }
                    .mapNotNull { tag -> ((tag as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull },
            )
        }

    override suspend fun getChapters(mangaId: String): List<Chapter> =
        runtime.withExtension { handle ->
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
        runtime.withExtension { handle ->
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
                val url = (page as? JsonPrimitive)?.contentOrNull
                    ?: throw ExtensionDataException("[$sourceId] page $index has no url: $page")
                Page(index = index, url = url)
            }
        }

    override suspend fun getHomeSections(): List<HomeSection> =
        runtime.withExtension { handle ->
            val extension = handle.extension(sourceId)
            // duck-type: capability flags are known-unreliable, so probe the method itself
            if (!extension.canInvokeMember("getDiscoverSections")) {
                return@withExtension emptyList()
            }
            handle.invokeAwait(extension, "initialise")
            val stubs = handle.invokeAwait(extension, "getDiscoverSections")
            if (!stubs.hasArrayElements()) {
                throw ExtensionDataException(
                    "[$sourceId] getDiscoverSections did not return an array: $stubs"
                )
            }
            val seenIds = mutableSetOf<String>()
            val sections = mutableListOf<HomeSection>()
            // A hostile bundle can return unbounded stubs, each costing a round-trip; truncate silently.
            for (i in 0 until minOf(stubs.arraySize, MAX_SECTION_STUBS)) {
                val stub = stubs.getArrayElement(i)
                if (!stub.hasMembers()) {
                    throw ExtensionDataException(
                        "[$sourceId] discover section stub $i is not an object: $stub"
                    )
                }
                val id = stub.getMember("id")?.takeIf { it.isString }?.asString()
                    ?: throw ExtensionDataException(
                        "[$sourceId] discover section stub $i has no string 'id': $stub"
                    )
                val title = stub.getMember("title")?.takeIf { it.isString }?.asString()
                    ?: throw ExtensionDataException(
                        "[$sourceId] discover section stub '$id' has no string 'title': $stub"
                    )
                // Skip genres stubs (type 4) before dedupe so they never claim an id a real section reuses.
                // fitsInInt: asInt throws on non-integers and out-of-range numbers.
                val type = stub.getMember("type")?.takeIf { it.isNumber && it.fitsInInt() }?.asInt()
                if (type == 4) continue
                if (!seenIds.add(id)) continue
                // Pass the original stub back: bundles read section.type and private fields off it.
                val raw = handle.invokeAwaitJson(extension, "getDiscoverSectionItems", stub, null)
                val result = Json.parseToJsonElement(raw) as? JsonObject
                    ?: throw ExtensionDataException(
                        "[$sourceId] getDiscoverSectionItems for '$id' did not return an object: ${raw.take(200)}"
                    )
                val items = result["items"] as? JsonArray
                    ?: throw ExtensionDataException(
                        "[$sourceId] discover section '$id' result has no items array: ${result.keys}"
                    )
                val entries = mangaEntriesOf(items)
                if (entries.isEmpty()) continue
                sections += HomeSection(id = id, title = title, items = entries)
            }
            sections
        }

    override suspend fun prepareImageRequests(requests: List<SourceImageRequest>): List<SourceImageRequest> =
        runtime.withExtension { handle ->
            val extension = handle.extension(sourceId)
            handle.invokeAwait(extension, "initialise")
            handle.runRequestInterceptors(requests)
        }

    private fun mangaEntriesOf(items: JsonArray): List<MangaEntry> = items.map { item ->
        val o = item.jsonObject
        MangaEntry(
            sourceId = sourceId,
            mangaId = o.requiredString("mangaId", sourceId),
            title = o.requiredString("title", sourceId),
            cover = (o["imageUrl"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun statusOf(raw: String?): MangaStatus =
        MangaStatus.entries.firstOrNull { it.name == raw?.uppercase() } ?: MangaStatus.UNKNOWN
}

private const val MAX_SECTION_STUBS = 32L

class ExtensionDataException(message: String) : Exception(message)

/** Extension output is a trust boundary: shape drift becomes a named error, not an NPE. */
internal fun JsonObject.requiredString(field: String, sourceId: String): String {
    val value = this[field]
    if (value is JsonPrimitive && value !is JsonNull) return value.content
    throw ExtensionDataException("[$sourceId] item is missing '$field': $this")
}
