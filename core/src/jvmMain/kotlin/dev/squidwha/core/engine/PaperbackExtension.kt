package dev.squidwha.core.engine

import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import dev.squidwha.core.domain.MangaEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * First slice of the Paperback adapter: one installed 0.9 extension, normalized to
 * domain types at this boundary. Grows into the full MangaSource implementation in M1.
 */
class PaperbackExtension(
    val sourceId: String,
    private val bundleJs: String,
    private val host: ApplicationHost,
) {
    init {
        // sourceId is interpolated into JS below; registry ids are third-party input
        require(sourceId.matches(Regex("[A-Za-z0-9_]+"))) { "invalid source id: $sourceId" }
    }

    suspend fun search(title: String, page: Int = 1): List<MangaEntry> =
        ExtensionRuntime(bundleJs, host).withExtension { qjs ->
            qjs.define("__query") {
                function("title") { title }
                function("page") { page }
            }
            qjs.callJson("source.$sourceId.initialise()")
            val result = Json.parseToJsonElement(
                qjs.callJson(
                    "source.$sourceId.getSearchResults(" +
                        "{ title: __query.title() }, { page: __query.page() }, { id: 'search' })"
                )
            ).jsonObject
            result["items"]!!.jsonArray.map { item ->
                val o = item.jsonObject
                MangaEntry(
                    sourceId = sourceId,
                    mangaId = o["mangaId"]!!.jsonPrimitive.content,
                    title = o["title"]!!.jsonPrimitive.content,
                    cover = o["imageUrl"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
}
