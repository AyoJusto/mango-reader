package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.AvailableSource
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.MangaSource
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceInfo
import dev.mango.core.engine.BundleLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val VERSIONING_JSON = """
{
  "sources": [
    { "id": "MangaBat", "name": "MangaBat", "description": "d1", "version": "2.0.0", "language": "en" },
    { "id": "FlameComics", "name": "Flame Comics", "description": "d2", "version": "1.0.0", "language": "en" }
  ]
}
"""

private const val TINY_BUNDLE = "// tiny fixture bundle\nfunction main() {}\n"

/** Integration tests for [InkdexRepo], driven only through the [dev.mango.core.domain.ExtensionRepo] contract. */
class InkdexRepoTest {
    private fun newDb(): MangoDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return MangoDatabase(driver)
    }

    private fun newCatalog(bundleDir: Path, db: MangoDatabase = newDb()): PaperbackCatalogRepository =
        PaperbackCatalogRepository(db, bundleDir, sourceFactory = { sourceId, _, _ -> stubMangaSource(sourceId) })

    private fun stubMangaSource(id: String): MangaSource = object : MangaSource {
        override val sourceId = id
        override suspend fun search(query: String, page: Int): List<MangaEntry> = emptyList()
        override suspend fun getDetails(mangaId: String): MangaDetails = error("not stubbed")
        override suspend fun getChapters(mangaId: String): List<Chapter> = emptyList()
        override suspend fun getPages(mangaId: String, chapterId: String): List<Page> = emptyList()
    }

    /** Routes GETs by path suffix: .../versioning.json vs .../<id>/index.js. */
    private fun engineFor(
        registryStatus: HttpStatusCode = HttpStatusCode.OK,
        bundleBytes: ByteArray = TINY_BUNDLE.encodeToByteArray(),
    ) = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/versioning.json") -> if (registryStatus.isSuccess()) {
                respond(VERSIONING_JSON, registryStatus, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("not found", registryStatus)
            }

            path.endsWith("/index.js") -> respond(bundleBytes, HttpStatusCode.OK)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun availableParsesAndSortsByName() = runTest {
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val repo = InkdexRepo(HttpClient(engineFor()), bundleDir, newCatalog(bundleDir))

        val sources = repo.available()

        // registry order is MangaBat then FlameComics; available() sorts by display name
        assertEquals(listOf("Flame Comics", "MangaBat"), sources.map { it.name })
        val flame = sources.first { it.sourceId == "FlameComics" }
        assertEquals("1.0.0", flame.version)
        assertEquals("d2", flame.description)
        assertEquals("en", flame.language)
    }

    @Test
    fun installWritesFileAndPinsARowResolvableThroughTheCatalog() = runTest {
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val catalog = newCatalog(bundleDir)
        val repo = InkdexRepo(HttpClient(engineFor()), bundleDir, catalog)

        repo.install(AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0"))

        assertTrue(Files.exists(bundleDir.resolve("FlameComics.index.js")))
        assertEquals(
            listOf(SourceInfo(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0")),
            catalog.installedSources(),
        )
        // resolves cleanly through the real repository: the pinned sha matches what was written
        assertTrue(catalog.search("FlameComics", "").isEmpty())
    }

    @Test
    fun oversizedBundleFailsBeforeAnyFileOrRowIsWritten() = runTest {
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val catalog = newCatalog(bundleDir)
        val oversized = ByteArray(BundleLoader.MAX_BUNDLE_BYTES + 1)
        val repo = InkdexRepo(HttpClient(engineFor(bundleBytes = oversized)), bundleDir, catalog)

        assertFailsWith<InkdexException> {
            repo.install(AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0"))
        }

        assertFalse(Files.exists(bundleDir.resolve("FlameComics.index.js")))
        assertTrue(catalog.installedSources().isEmpty())
    }

    @Test
    fun declaredContentLengthOverCapIsRejectedWithoutReadingTheBody() = runTest {
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val catalog = newCatalog(bundleDir)
        val oversized = ByteArray(BundleLoader.MAX_BUNDLE_BYTES + 1)
        val oversizedLength = oversized.size.toString()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/versioning.json") -> respond(VERSIONING_JSON, HttpStatusCode.OK)
                path.endsWith("/index.js") -> respond(
                    oversized,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength, oversizedLength),
                )

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = InkdexRepo(HttpClient(engine), bundleDir, catalog)

        assertFailsWith<InkdexException> {
            repo.install(AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0"))
        }

        assertFalse(Files.exists(bundleDir.resolve("FlameComics.index.js")))
        assertTrue(catalog.installedSources().isEmpty())
    }

    @Test
    fun unsafeSourceIdIsRejectedBeforeAnyNetworkCall() = runTest {
        var networkCalled = false
        val engine = MockEngine {
            networkCalled = true
            respond("", HttpStatusCode.OK)
        }
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val repo = InkdexRepo(HttpClient(engine), bundleDir, newCatalog(bundleDir))

        assertFailsWith<IllegalArgumentException> {
            repo.install(AvailableSource(sourceId = "../evil", name = "Evil", version = "1.0.0"))
        }
        assertFalse(networkCalled, "expected the unsafe id to be rejected before any request went out")
    }

    @Test
    fun postInstallTamperIsRejectedOnColdResolve() = runTest {
        // TOFU's actual promise: the pin protects the bundle AFTER install. Swap the file on
        // disk, resolve through a fresh repository (cold cache), and verification must fail.
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val db = newDb()
        val repo = InkdexRepo(HttpClient(engineFor()), bundleDir, newCatalog(bundleDir, db))
        repo.install(AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0"))

        Files.write(bundleDir.resolve("FlameComics.index.js"), "swapped bytes".encodeToByteArray())

        val coldCatalog = newCatalog(bundleDir, db)
        assertFailsWith<dev.mango.core.engine.BundleVerificationException> {
            coldCatalog.search("FlameComics", "")
        }
    }

    @Test
    fun updateSwapsTheBundleAndPreservesThePinnedUserAgent() = runTest {
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val db = newDb()
        val servedBundles = mutableListOf<String>()
        val catalog = PaperbackCatalogRepository(db, bundleDir, sourceFactory = { sourceId, bundleJs, _ ->
            servedBundles += bundleJs
            stubMangaSource(sourceId)
        })
        var bundle = "// v1 bundle\n"
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/versioning.json") -> respond(VERSIONING_JSON, HttpStatusCode.OK)
                path.endsWith("/index.js") -> respond(bundle.encodeToByteArray(), HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = InkdexRepo(HttpClient(engine), bundleDir, catalog)
        val source = AvailableSource(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0")
        repo.install(source)
        catalog.search("FlameComics", "")
        // a UA pinned by the challenge flow must survive a reinstall/update
        db.sourcesQueries.updateUserAgent(user_agent = "pinned-ua", source_id = "FlameComics")

        bundle = "// v2 bundle\n"
        repo.install(source.copy(version = "2.0.0"))
        catalog.search("FlameComics", "")

        assertEquals(listOf("// v1 bundle\n", "// v2 bundle\n"), servedBundles)
        assertEquals(
            "pinned-ua",
            db.sourcesQueries.selectInstalledSource("FlameComics").executeAsOne().user_agent,
        )
    }

    @Test
    fun registryFailureThrowsANamedError() = runTest {
        val bundleDir = Files.createTempDirectory("inkdex-test")
        val repo = InkdexRepo(
            HttpClient(engineFor(registryStatus = HttpStatusCode.NotFound)),
            bundleDir,
            newCatalog(bundleDir),
        )

        assertFailsWith<InkdexException> { repo.available() }
    }
}
