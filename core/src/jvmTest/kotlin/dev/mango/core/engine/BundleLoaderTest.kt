package dev.mango.core.engine

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

const val FLAME_COMICS_FIXTURE = "FlameComics.index.js"
const val FLAME_COMICS_SHA256 = "7bc0747ee748f812b9b42d585b83e6da0f9c45c6467a0044b22ad77ae144629a"
const val VERSIONING_SHA256 = "adc1923c9760b85e020fcdc1688f28956605b3415a4627c1a9ba26012e4a1e4d"

/**
 * Registry entries served by [readFixture]: name -> (subpath under the pinned registry
 * commit, expected sha256). The registry commit is immutable, so a cached file whose hash
 * matches is trusted forever; a mismatch means a stale/corrupt cache entry, not a moved target.
 */
private val FIXTURE_REGISTRY: Map<String, Pair<String, String>> = mapOf(
    FLAME_COMICS_FIXTURE to ("FlameComics/index.js" to FLAME_COMICS_SHA256),
    MANGABAT_FIXTURE to ("MangaBat/index.js" to MANGABAT_SHA256),
    "versioning.json" to ("versioning.json" to VERSIONING_SHA256),
)

private const val REGISTRY_BASE_URL =
    "https://raw.githubusercontent.com/inkdex/extensions/f094e9445762d3951c2f4dfb8d2ca3b0f801c3f5/0.9/stable/"

private val fixtureCacheDir: Path = Paths.get(System.getProperty("user.dir"), "build", "fixture-cache")

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Downloads and caches extension bundle fixtures from the pinned Inkdex registry commit,
 * verifying each against its expected sha256. All lookups share one lock so concurrent test
 * classes in a single JVM run don't race the same download.
 */
internal fun readFixture(name: String): ByteArray = synchronized(FIXTURE_REGISTRY) {
    val (subpath, expectedSha) = checkNotNull(FIXTURE_REGISTRY[name]) {
        "unknown fixture $name; add it to FIXTURE_REGISTRY in BundleLoaderTest.kt"
    }
    val cached = fixtureCacheDir.resolve(name)

    if (Files.exists(cached)) {
        val bytes = Files.readAllBytes(cached)
        if (sha256Hex(bytes) == expectedSha) return@synchronized bytes
        Files.delete(cached)
    }

    val url = REGISTRY_BASE_URL + subpath
    val bytes = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) {
            "failed to download fixture $name from $url: HTTP ${response.statusCode()}"
        }
        response.body()
    } catch (e: Exception) {
        throw IllegalStateException("failed to download fixture $name from $url: ${e.message}", e)
    }

    val actualSha = sha256Hex(bytes)
    check(actualSha == expectedSha) {
        "checksum mismatch for freshly downloaded fixture $name from $url: " +
            "expected $expectedSha, got $actualSha"
    }

    Files.createDirectories(fixtureCacheDir)
    val tmp = Files.createTempFile(fixtureCacheDir, name, ".tmp")
    Files.write(tmp, bytes)
    Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

    bytes
}

class BundleLoaderTest {
    @Test
    fun acceptsBundleWithMatchingChecksum() {
        val js = BundleLoader.verify(readFixture(FLAME_COMICS_FIXTURE), FLAME_COMICS_SHA256)
        assertTrue(js.isNotEmpty())
    }

    @Test
    fun rejectsChecksumMismatch() {
        val e = assertFailsWith<BundleVerificationException> {
            BundleLoader.verify(readFixture(FLAME_COMICS_FIXTURE), "0".repeat(64))
        }
        assertTrue("checksum mismatch" in e.message!!)
    }

    @Test
    fun rejectsOversizedBundle() {
        val e = assertFailsWith<BundleVerificationException> {
            BundleLoader.verify(ByteArray(BundleLoader.MAX_BUNDLE_BYTES + 1), FLAME_COMICS_SHA256)
        }
        assertTrue("max allowed" in e.message!!)
    }

    @Test
    fun rejectsInvalidUtf8EvenWithMatchingChecksum() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val e = assertFailsWith<BundleVerificationException> {
            BundleLoader.verify(bytes, sha)
        }
        assertTrue("not valid UTF-8" in e.message!!)
    }
}
