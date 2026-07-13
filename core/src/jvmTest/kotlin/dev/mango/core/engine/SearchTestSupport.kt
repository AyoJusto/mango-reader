package dev.mango.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.graalvm.polyglot.proxy.ProxyObject
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

object RecordedHttp {
    fun fixtureName(url: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.encodeToByteArray()).toHexString().take(12)
        val slug = url.substringAfter("://").replace(Regex("[^A-Za-z0-9.-]"), "_").take(80)
        return "http/${slug}_$hash.bin"
    }

    fun replayClient(): HttpClient = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        val name = fixtureName(url)
        val bytes = javaClass.getResourceAsStream("/fixtures/$name")?.readBytes()
            ?: error(
                "no recorded fixture for $url (expected fixtures/$name); " +
                        "re-record with: gradlew :core:jvmTest -Plive --tests *LiveRecord*"
            )
        respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    /** Replay host for recorded fixtures: no politeness delay, no-op sleep — there is no real server to protect. */
    fun replayHost(): ApplicationHost =
        ApplicationHost(http = replayClient(), minRequestIntervalMillis = 0, sleeper = {})

    /** CIO client + fixture-writing onResponse, shared by the LiveRecord* tests. Any non-200 fails the run. */
    fun recordingHost(): ApplicationHost {
        val fixturesDir = Paths.get("src", "jvmTest", "resources", "fixtures")
        return ApplicationHost(
            http = HttpClient(CIO),
            onResponse = { url, status, body ->
                check(status == 200) { "live request to $url returned $status" }
                val file = fixturesDir.resolve(fixtureName(url))
                Files.createDirectories(file.parent)
                Files.write(file, body)
                println("recorded $url -> ${file.fileName} (${body.size} bytes)")
            },
        )
    }
}

internal const val MANGABAT_FIXTURE = "MangaBat.index.js"
internal const val MANGABAT_SHA256 = "ec9a212b2ebc2354619bc30ef24836e838eebc51a50a42eccd9e733211d3d08f"
internal const val TOONILY_FIXTURE = "Toonily.index.js"
internal const val TOONILY_SHA256 = "22aaade46f110a4eb27a9355d373c09d6ea4ad61f9af3478883fd6a38dd323ae"
internal const val WEBTOONXYZ_FIXTURE = "WebtoonXYZ.index.js"
internal const val WEBTOONXYZ_SHA256 = "a15b60c2435f25fd17212f2c9cef24c63a413380371341e353f43008cc271a91"

internal val flameComicsBundle: String by lazy {
    BundleLoader.verify(readFixture(FLAME_COMICS_FIXTURE), FLAME_COMICS_SHA256)
}

internal val mangaBatBundle: String by lazy {
    BundleLoader.verify(readFixture(MANGABAT_FIXTURE), MANGABAT_SHA256)
}

internal val toonilyBundle: String by lazy {
    BundleLoader.verify(readFixture(TOONILY_FIXTURE), TOONILY_SHA256)
}

internal val webtoonXyzBundle: String by lazy {
    BundleLoader.verify(readFixture(WEBTOONXYZ_FIXTURE), WEBTOONXYZ_SHA256)
}

/** initialise() + getSearchResults through the full runtime, raw result as JsonObject. */
internal suspend fun runFlameComicsSearch(host: ApplicationHost, title: String): JsonObject =
    ExtensionRuntime(flameComicsBundle, host).withExtension { handle ->
        val extension = handle.extension("FlameComics")
        handle.invokeAwait(extension, "initialise")
        Json.parseToJsonElement(
            handle.invokeAwaitJson(
                extension,
                "getSearchResults",
                ProxyObject.fromMap(mapOf("title" to title)),
                ProxyObject.fromMap(mapOf<String, Any>()),
                ProxyObject.fromMap(mapOf("id" to "search")),
            )
        ).jsonObject
    }
