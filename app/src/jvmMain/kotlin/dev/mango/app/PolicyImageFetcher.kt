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

/**
 * Marks this request to bypass Coil's default network fetcher for header delivery: the entries
 * in [headers] reach the wire through [PolicyImageFetcher] instead of Coil's own network stack,
 * with names sent exactly as given.
 */
fun ImageRequest.Builder.policyHeaders(headers: Map<String, String>): ImageRequest.Builder = apply {
    extras[policyHeadersKey] = headers
}

/** The headers set by [policyHeaders], or null if the request never carried any. */
val ImageRequest.policyHeaders: Map<String, String>?
    get() = getExtra(policyHeadersKey)

/**
 * A coil3 [Fetcher] for http/https image requests carrying [policyHeaders]. Coil's own network
 * fetcher (coil3-network-ktor3) stores request headers in a `NetworkHeaders`, whose keys are
 * lowercased on write; some sources 403 an otherwise-valid, cookie-bearing request when its
 * header NAMES arrive lowercased over HTTP/1.1 — the casing itself is part of the site's bot
 * fingerprint, the same one [dev.mango.core.engine.canonicalHeaderName] exists to defeat on the
 * extension-call path. This fetcher sends [headers] through [http] with names appended verbatim
 * (ktor preserves the exact casing a caller appends), so a request already built with canonical
 * casing by a header policy reaches the wire unchanged.
 *
 * Claims a request only when [policyHeaders] was set on it and the model is an http/https URL;
 * otherwise [Factory.create] returns null and Coil falls through to its default network fetcher,
 * so requests carrying no policy headers are unaffected.
 *
 * Participates in the [ImageLoader]'s disk cache the same way Coil's own network fetcher does:
 * a custom [Fetcher] replaces Coil's network layer entirely, and with it the disk caching that
 * layer normally provides, so this fetcher reimplements the snapshot-or-fetch-and-write flow
 * itself rather than losing on-disk caching as a side effect of sending headers verbatim.
 */
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

        // named requestHeaders: inside this builder lambda, "headers" would resolve to
        // HttpRequestBuilder's own property instead of this class's field.
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

    /** Null return (editor unavailable, or the write itself failed) degrades to the caller's buffered-bytes fallback. */
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
