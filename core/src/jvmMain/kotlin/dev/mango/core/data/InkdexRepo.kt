package dev.mango.core.data

import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.ExtensionRepo
import dev.mango.core.domain.SourceInfo
import dev.mango.core.engine.BundleLoader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** A registry listing or bundle download from the Inkdex host failed. */
class InkdexException(message: String) : IOException(message)

/**
 * [ExtensionRepo] backed by the Inkdex static extension registry: a `versioning.json` listing
 * plus one `<id>/index.js` bundle per source, both served flat off [baseUrl].
 *
 * Trust model is TOFU (trust-on-first-install): [install] computes the pinned sha256 from the
 * bytes it just downloaded, so the pin protects a source against tampering or a bundle swap
 * *after* install — a later fetch that lands on different bytes than what's pinned fails
 * [BundleLoader.verify] on the next resolve (see [PaperbackCatalogRepository]). It does not
 * protect against a malicious or compromised registry serving a bad bundle on first install;
 * nothing short of an out-of-band pin (outside the Inkdex registry's contract) can. Updating a
 * source is the same call as installing it: [CatalogRepository.install] evicts the resolved
 * cache, and the bundle file overwrite here is the swap.
 *
 * DTO parsing is manual [JsonObject] navigation, not `@Serializable` classes: :core has no
 * kotlin-serialization compiler plugin applied (only the `kotlinx-serialization-json` runtime,
 * used elsewhere for the same manual style — see PaperbackExtension).
 */
class InkdexRepo(
    private val http: HttpClient,
    private val bundleDir: Path,
    private val catalog: CatalogRepository,
    private val baseUrl: String = "https://inkdex.github.io/extensions/0.9/stable",
    private val context: CoroutineContext = Dispatchers.Default,
) : ExtensionRepo {

    private data class RegistryEntry(
        val id: String,
        val name: String,
        val description: String?,
        val version: String,
        val language: String?,
    )

    override suspend fun available(): List<AvailableSource> = withContext(context) {
        val url = "$baseUrl/versioning.json"
        val response = http.get(url)
        if (!response.status.isSuccess()) {
            throw InkdexException("GET $url failed with status ${response.status}")
        }
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val sources = root["sources"] as? JsonArray
            ?: throw InkdexException("versioning.json has no 'sources' array: ${root.keys}")
        sources.map { it.jsonObject.toRegistryEntry() }
            .map { entry ->
                AvailableSource(
                    sourceId = entry.id,
                    name = entry.name,
                    version = entry.version,
                    description = entry.description,
                    language = entry.language,
                )
            }
            .sortedBy { it.name }
    }

    override suspend fun install(source: AvailableSource): Unit = withContext(context) {
        // validated before any network call: sourceId ends up in a filesystem path below
        requireSafeSourceId(source.sourceId)

        val url = "$baseUrl/${source.sourceId}/index.js"
        val response = http.get(url)
        if (!response.status.isSuccess()) {
            throw InkdexException("GET $url failed with status ${response.status}")
        }
        val bytes = response.body<ByteArray>()
        // the download owns this cap (BundleLoader's in-memory check is defense-in-depth on
        // top of it) — enforced before anything touches disk or the DB
        if (bytes.size > BundleLoader.MAX_BUNDLE_BYTES) {
            throw InkdexException(
                "bundle for ${source.sourceId} is ${bytes.size} bytes, " +
                    "max allowed is ${BundleLoader.MAX_BUNDLE_BYTES}"
            )
        }
        val sha256 = sha256Hex(bytes)
        // sanity roundtrip against the hash we just computed ourselves: this can only ever
        // fail on a UTF-8 violation, which is exactly the point (guards decodeToString later)
        BundleLoader.verify(bytes, sha256)

        Files.createDirectories(bundleDir)
        Files.write(bundleDir.resolve("${source.sourceId}.index.js"), bytes)

        catalog.install(
            SourceInfo(sourceId = source.sourceId, name = source.name, version = source.version),
            sha256,
        )
    }

    private fun JsonObject.toRegistryEntry(): RegistryEntry = RegistryEntry(
        id = requiredString("id"),
        name = requiredString("name"),
        description = (this["description"] as? JsonPrimitive)?.contentOrNull,
        version = requiredString("version"),
        language = (this["language"] as? JsonPrimitive)?.contentOrNull,
    )

    private fun JsonObject.requiredString(field: String): String =
        (this[field] as? JsonPrimitive)?.contentOrNull
            ?: throw InkdexException("registry entry is missing '$field': $this")

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}
