package dev.mango.app

import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import okio.Buffer
import okio.FileSystem

private val policyHeadersKey = Extras.Key<Map<String, String>?>(default = null)

/** Routes [headers] through PolicyImageFetcher with names sent exactly as given. */
fun ImageRequest.Builder.policyHeaders(headers: Map<String, String>): ImageRequest.Builder = apply {
    extras[policyHeadersKey] = headers
}

val ImageRequest.policyHeaders: Map<String, String>?
    get() = getExtra(policyHeadersKey)

/** Sends policy headers with verbatim name casing (Coil lowercases them) and does its own disk caching. */
class PolicyImageFetcher(
    private val url: String,
    private val diskCacheKey: String,
    private val requestHeaders: Map<String, String>,
    private val http: HttpClient,
    private val fileSystem: FileSystem,
    private val diskCache: DiskCache?,
    private val diskCachePolicy: CachePolicy,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cache = diskCache
        if (cache != null && diskCachePolicy.readEnabled) {
            val snapshot = runCatching { cache.openSnapshot(diskCacheKey) }.getOrNull()
            if (snapshot != null) {
                return SourceFetchResult(
                    source = snapshot.toImageSource(cache),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }

        // Not named headers: that would resolve to HttpRequestBuilder's property.
        val response = http.get(url) { requestHeaders.forEach { (name, value) -> header(name, value) } }
        check(response.status.isSuccess()) { "policy image fetch failed: $url -> ${response.status}" }
        val mimeType = response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" }
        val bytes = response.body<ByteArray>()

        if (cache != null && diskCachePolicy.writeEnabled) {
            val snapshot = writeToDiskCache(cache, bytes)
            if (snapshot != null) {
                return SourceFetchResult(
                    source = snapshot.toImageSource(cache),
                    mimeType = mimeType,
                    dataSource = DataSource.NETWORK,
                )
            }
        }

        return SourceFetchResult(
            source = ImageSource(source = Buffer().apply { write(bytes) }, fileSystem = fileSystem),
            mimeType = mimeType,
            dataSource = DataSource.NETWORK,
        )
    }

    /** Null on editor unavailable or write failure; the caller falls back to buffered bytes. */
    private fun writeToDiskCache(cache: DiskCache, bytes: ByteArray): DiskCache.Snapshot? {
        val editor = runCatching { cache.openEditor(diskCacheKey) }.getOrNull() ?: return null
        return try {
            cache.fileSystem.write(editor.data) { write(bytes) }
            editor.commitAndOpenSnapshot()
        } catch (_: Exception) {
            editor.abort()
            null
        }
    }

    private fun DiskCache.Snapshot.toImageSource(cache: DiskCache): ImageSource =
        ImageSource(file = data, fileSystem = cache.fileSystem, diskCacheKey = diskCacheKey, closeable = this)

    class Factory(private val http: HttpClient) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val headers = options.extras[policyHeadersKey] ?: return null
            if (data.scheme != "http" && data.scheme != "https") return null
            val url = data.toString()
            return PolicyImageFetcher(
                url = url,
                diskCacheKey = options.diskCacheKey ?: url,
                requestHeaders = headers,
                http = http,
                fileSystem = options.fileSystem,
                diskCache = imageLoader.diskCache,
                diskCachePolicy = options.diskCachePolicy,
            )
        }
    }
}
