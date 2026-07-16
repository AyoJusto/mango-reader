package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.Chapter
import dev.mango.core.domain.DownloadManager
import dev.mango.core.domain.DownloadStatus
import dev.mango.core.domain.HomeSection
import dev.mango.core.domain.MangaDetails
import dev.mango.core.domain.MangaEntry
import dev.mango.core.domain.Page
import dev.mango.core.domain.SourceHeaderPolicy
import dev.mango.core.domain.SourceInfo
import dev.mango.core.domain.StoredCookie
import dev.mango.core.engine.DefaultSourceHeaderPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Integration tests for [FileDownloadManager], driven only through the [DownloadManager] contract. */
class DownloadManagerTest {
    /** Canned pages per "sourceId/mangaId/chapterId", the only member these tests exercise. */
    private class FakeCatalogRepository(private val pages: Map<String, List<Page>>) : CatalogRepository {
        override suspend fun installedSources(): List<SourceInfo> = throw UnsupportedOperationException()
        override suspend fun install(info: SourceInfo, bundleSha256: String): Unit =
            throw UnsupportedOperationException()

        override suspend fun search(sourceId: String, query: String, page: Int): List<MangaEntry> =
            throw UnsupportedOperationException()

        override suspend fun homeSections(sourceId: String): List<HomeSection> =
            throw UnsupportedOperationException()

        override suspend fun details(sourceId: String, mangaId: String): MangaDetails =
            throw UnsupportedOperationException()

        override suspend fun chapters(sourceId: String, mangaId: String): List<Chapter> =
            throw UnsupportedOperationException()

        override suspend fun pages(sourceId: String, mangaId: String, chapterId: String): List<Page> =
            pages["$sourceId/$mangaId/$chapterId"] ?: error("no fixture pages for $sourceId/$mangaId/$chapterId")

        override suspend fun setUserAgent(sourceId: String, userAgent: String): Unit =
            throw UnsupportedOperationException()

        override suspend fun uninstall(sourceId: String): Unit = throw UnsupportedOperationException()
    }

    private fun mockClient(
        bytesByUrl: Map<String, ByteArray>,
        failingUrls: Set<String> = emptySet(),
        recorded: MutableList<HttpRequestData> = mutableListOf(),
    ): HttpClient = HttpClient(MockEngine { request ->
        recorded += request
        val url = request.url.toString()
        if (url in failingUrls) {
            respond(ByteArray(0), HttpStatusCode.InternalServerError)
        } else {
            val bytes = bytesByUrl[url] ?: error("no fixture bytes for $url")
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
    })

    private fun entry(sourceId: String, mangaId: String, title: String = "Title") =
        MangaEntry(sourceId = sourceId, mangaId = mangaId, title = title)

    private fun chapter(chapterId: String, number: Double = 1.0) =
        Chapter(chapterId = chapterId, number = number)

    private fun newManager(
        catalog: CatalogRepository,
        http: HttpClient,
        root: Path,
        headerPolicy: SourceHeaderPolicy? = null,
    ): DownloadManager {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return FileDownloadManager(
            db = MangoDatabase(driver),
            catalog = catalog,
            http = http,
            root = root,
            pageDelayMillis = 0,
            headerPolicy = headerPolicy,
        )
    }

    @Test
    fun enqueueThenProcessQueueDownloadsAllPagesAndMarksDone() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val pages = listOf(
            Page(index = 0, url = "https://cdn.example/c1/0.jpg", headers = mapOf("Referer" to "https://example/m1")),
            Page(index = 1, url = "https://cdn.example/c1/1.jpg", headers = mapOf("Referer" to "https://example/m1")),
        )
        val bytesByUrl = mapOf(pages[0].url to byteArrayOf(1, 2, 3), pages[1].url to byteArrayOf(4, 5, 6, 7))
        val recorded = mutableListOf<HttpRequestData>()
        val manager = newManager(
            FakeCatalogRepository(mapOf("src/m1/c1" to pages)),
            mockClient(bytesByUrl, recorded = recorded),
            root,
        )

        manager.enqueue(entry("src", "m1", title = "Solo Leveling"), chapter("c1", number = 3.5))
        manager.processQueue()

        val file0 = root.resolve("src/m1/c1/0000.jpg")
        val file1 = root.resolve("src/m1/c1/0001.jpg")
        assertTrue(Files.exists(file0))
        assertTrue(Files.exists(file1))
        assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(file0).toList())
        assertEquals(listOf<Byte>(4, 5, 6, 7), Files.readAllBytes(file1).toList())

        val row = manager.observeDownloads().first().single()
        assertEquals(DownloadStatus.DONE, row.status)
        assertEquals(2, row.pagesTotal)
        assertEquals(2, row.pagesDone)
        // title/number round-trip through the DB row, not just the in-memory Download passed in
        assertEquals("Solo Leveling", row.mangaTitle)
        assertEquals(3.5, row.chapterNumber)

        assertTrue(recorded.isNotEmpty())
        assertTrue(recorded.all { it.headers[HttpHeaders.Referrer] == "https://example/m1" })
    }

    @Test
    fun urlWithoutAnExtensionFallsBackToImg() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val page = Page(index = 0, url = "https://cdn.example/c1/page-one")
        val manager = newManager(
            FakeCatalogRepository(mapOf("src/m1/c1" to listOf(page))),
            mockClient(mapOf(page.url to byteArrayOf(9))),
            root,
        )

        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()

        assertTrue(Files.exists(root.resolve("src/m1/c1/0000.img")))
    }

    @Test
    fun aFailingPageFailsItsChapterButALaterQueuedChapterStillCompletes() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val badPages = listOf(
            Page(index = 0, url = "https://cdn.example/bad/0.jpg"),
            Page(index = 1, url = "https://cdn.example/bad/1.jpg"),
        )
        val goodPages = listOf(Page(index = 0, url = "https://cdn.example/good/0.jpg"))
        val bytesByUrl = mapOf(
            badPages[0].url to byteArrayOf(1),
            badPages[1].url to byteArrayOf(2),
            goodPages[0].url to byteArrayOf(3),
        )
        val manager = newManager(
            FakeCatalogRepository(mapOf("src/bad/c1" to badPages, "src/good/c1" to goodPages)),
            mockClient(bytesByUrl, failingUrls = setOf(badPages[1].url)),
            root,
        )

        manager.enqueue(entry("src", "bad"), chapter("c1"))
        manager.enqueue(entry("src", "good"), chapter("c1"))
        manager.processQueue()

        assertTrue(Files.exists(root.resolve("src/bad/c1/0000.jpg")))
        assertFalse(Files.exists(root.resolve("src/bad/c1/0001.jpg")))

        val rowsByMangaId = manager.observeDownloads().first().associateBy { it.mangaId }
        assertEquals(DownloadStatus.FAILED, rowsByMangaId.getValue("bad").status)
        assertEquals(DownloadStatus.DONE, rowsByMangaId.getValue("good").status)
    }

    @Test
    fun reEnqueueingAFailedChapterRecoversOnTheNextProcessQueue() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val page = Page(index = 0, url = "https://cdn.example/c1/0.jpg")
        var shouldFail = true
        val http = HttpClient(MockEngine {
            if (shouldFail) respond(ByteArray(0), HttpStatusCode.InternalServerError)
            else respond(byteArrayOf(5), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })
        val manager = newManager(FakeCatalogRepository(mapOf("src/m1/c1" to listOf(page))), http, root)

        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()
        assertEquals(DownloadStatus.FAILED, manager.observeDownloads().first().single().status)
        assertFalse(Files.exists(root.resolve("src/m1/c1/0000.jpg")))

        shouldFail = false
        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()

        val row = manager.observeDownloads().first().single()
        assertEquals(DownloadStatus.DONE, row.status)
        assertTrue(Files.exists(root.resolve("src/m1/c1/0000.jpg")))
    }

    @Test
    fun hostileIdsCannotEscapeTheDownloadRoot() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        // Path.resolve walks up on ".." and discards the base entirely for absolute args
        val relativeEscape = "..\\..\\evil"
        val absoluteEscape = root.parent.resolve("abs-escape").toString()
        val page = Page(index = 0, url = "https://cdn.example/c1/0.jpg")
        val manager = newManager(
            FakeCatalogRepository(mapOf("src/$relativeEscape/$absoluteEscape" to listOf(page))),
            mockClient(mapOf(page.url to byteArrayOf(1))),
            root,
        )

        manager.enqueue(entry("src", relativeEscape), chapter(absoluteEscape))
        manager.processQueue()

        assertEquals(DownloadStatus.DONE, manager.observeDownloads().first().single().status)
        assertFalse(Files.exists(root.parent.resolve("evil")))
        assertFalse(Files.exists(root.parent.resolve("abs-escape")))
        val written = Files.walk(root).use { stream -> stream.filter(Files::isRegularFile).toList() }
        assertEquals(1, written.size, "expected the page to land inside the download root")
    }

    @Test
    fun observeDownloadsEmitsTheRowAfterEnqueueWithStatusQueued() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val manager = newManager(FakeCatalogRepository(emptyMap()), mockClient(emptyMap()), root)

        manager.enqueue(entry("src", "m1", title = "Nano Machine"), chapter("c1", number = 12.0))

        val row = manager.observeDownloads().first().single()
        assertEquals(DownloadStatus.QUEUED, row.status)
        assertEquals(0, row.pagesTotal)
        assertEquals(0, row.pagesDone)
        assertEquals("Nano Machine", row.mangaTitle)
        assertEquals(12.0, row.chapterNumber)
    }

    @Test
    fun localPagesReturnsOrderedAbsolutePathsAfterASuccessfulDownload() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val pages = listOf(
            Page(index = 0, url = "https://cdn.example/c1/0.jpg"),
            Page(index = 1, url = "https://cdn.example/c1/1.jpg"),
        )
        val bytesByUrl = mapOf(pages[0].url to byteArrayOf(1, 2, 3), pages[1].url to byteArrayOf(4, 5, 6, 7))
        val manager = newManager(FakeCatalogRepository(mapOf("src/m1/c1" to pages)), mockClient(bytesByUrl), root)

        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()

        assertEquals(
            listOf(
                root.resolve("src/m1/c1/0000.jpg").toAbsolutePath().toString(),
                root.resolve("src/m1/c1/0001.jpg").toAbsolutePath().toString(),
            ),
            manager.localPages("src", "m1", "c1"),
        )
    }

    @Test
    fun localPagesIsNullWhenThereIsNoRowForTheKey() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val manager = newManager(FakeCatalogRepository(emptyMap()), mockClient(emptyMap()), root)

        assertNull(manager.localPages("src", "m1", "c1"))
    }

    @Test
    fun localPagesIsNullWhenTheChapterFailed() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val page = Page(index = 0, url = "https://cdn.example/c1/0.jpg")
        val manager = newManager(
            FakeCatalogRepository(mapOf("src/m1/c1" to listOf(page))),
            mockClient(emptyMap(), failingUrls = setOf(page.url)),
            root,
        )

        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()

        assertEquals(DownloadStatus.FAILED, manager.observeDownloads().first().single().status)
        assertNull(manager.localPages("src", "m1", "c1"))
    }

    @Test
    fun localPagesIsNullWhenDoneButAFileWasDeletedFromDiskAfterward() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val pages = listOf(
            Page(index = 0, url = "https://cdn.example/c1/0.jpg"),
            Page(index = 1, url = "https://cdn.example/c1/1.jpg"),
        )
        val bytesByUrl = mapOf(pages[0].url to byteArrayOf(1), pages[1].url to byteArrayOf(2))
        val manager = newManager(FakeCatalogRepository(mapOf("src/m1/c1" to pages)), mockClient(bytesByUrl), root)

        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()
        Files.delete(root.resolve("src/m1/c1/0001.jpg"))

        assertNull(manager.localPages("src", "m1", "c1"))
    }

    @Test
    fun clearDownloadsRemovesTheMangasRowsAndFiles() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val page = Page(index = 0, url = "https://cdn.example/c1/0.jpg")
        val manager = newManager(
            FakeCatalogRepository(mapOf("src/m1/c1" to listOf(page))),
            mockClient(mapOf(page.url to byteArrayOf(1))),
            root,
        )

        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()
        assertEquals(DownloadStatus.DONE, manager.observeDownloads().first().single().status)

        manager.clearDownloads("src", "m1")

        assertNull(manager.localPages("src", "m1", "c1"))
        assertTrue(manager.observeDownloads().first().isEmpty())
        assertFalse(Files.exists(root.resolve("src/m1")))
    }

    @Test
    fun clearDownloadsOnlyAffectsTheClearedManga() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val pageA = Page(index = 0, url = "https://cdn.example/a/0.jpg")
        val pageB = Page(index = 0, url = "https://cdn.example/b/0.jpg")
        val manager = newManager(
            FakeCatalogRepository(mapOf("src/mangaA/c1" to listOf(pageA), "src/mangaB/c1" to listOf(pageB))),
            mockClient(mapOf(pageA.url to byteArrayOf(1), pageB.url to byteArrayOf(2))),
            root,
        )

        manager.enqueue(entry("src", "mangaA"), chapter("c1"))
        manager.enqueue(entry("src", "mangaB"), chapter("c1"))
        manager.processQueue()

        manager.clearDownloads("src", "mangaA")

        assertNull(manager.localPages("src", "mangaA", "c1"))
        assertEquals(
            listOf(root.resolve("src/mangaB/c1/0000.jpg").toAbsolutePath().toString()),
            manager.localPages("src", "mangaB", "c1"),
        )
    }

    @Test
    fun aWiredHeaderPolicyAppliesTheJarCookieAndPinnedUserAgentToImageFetches() = runTest {
        val root = Files.createTempDirectory("downloads-test")
        val page = Page(index = 0, url = "https://cdn.example/c1/0.jpg")
        val recorded = mutableListOf<HttpRequestData>()

        val cookieDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(cookieDriver)
        val cookieStore = SqlCookieStore(MangoDatabase(cookieDriver), "src")
        cookieStore.put(StoredCookie(name = "cf_clearance", value = "token", domain = "cdn.example"))
        val policy = DefaultSourceHeaderPolicy(cookieStoreFor = { cookieStore }, userAgentFor = { "Pinned/1.0" })

        val manager = newManager(
            FakeCatalogRepository(mapOf("src/m1/c1" to listOf(page))),
            mockClient(mapOf(page.url to byteArrayOf(1)), recorded = recorded),
            root,
            headerPolicy = policy,
        )

        manager.enqueue(entry("src", "m1"), chapter("c1"))
        manager.processQueue()

        val headers = recorded.single().headers
        assertEquals("cf_clearance=token", headers[HttpHeaders.Cookie])
        assertEquals("Pinned/1.0", headers[HttpHeaders.UserAgent])
    }
}
