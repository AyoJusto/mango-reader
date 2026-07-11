package dev.mango.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.mango.core.db.MangoDatabase
import dev.mango.core.domain.CatalogRepository
import dev.mango.core.domain.MangaSource
import dev.mango.core.domain.SourceInfo
import dev.mango.core.engine.ApplicationHost
import dev.mango.core.engine.BundleVerificationException
import dev.mango.core.engine.FLAME_COMICS_FIXTURE
import dev.mango.core.engine.FLAME_COMICS_SHA256
import dev.mango.core.engine.PaperbackExtension
import dev.mango.core.engine.RecordedHttp
import dev.mango.core.engine.readFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Integration tests for [PaperbackCatalogRepository], driven only through the [CatalogRepository] contract. */
class CatalogRepositoryTest {
    private fun newDb(): MangoDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        MangoDatabase.Schema.create(driver)
        return MangoDatabase(driver)
    }

    /** A bundleDir with the real FlameComics fixture written as <sourceId>.index.js. */
    private fun bundleDirWithFlameComics(): Path {
        val dir = Files.createTempDirectory("catalog-test")
        Files.write(dir.resolve("FlameComics.index.js"), readFixture(FLAME_COMICS_FIXTURE))
        return dir
    }

    private fun newRepository(
        bundleDir: Path,
        db: MangoDatabase = newDb(),
        sourceFactory: (String, String, String?) -> MangaSource = { sourceId, bundleJs, _ ->
            PaperbackExtension(sourceId, bundleJs, ApplicationHost(http = RecordedHttp.replayClient()))
        },
    ): CatalogRepository = PaperbackCatalogRepository(db, bundleDir, sourceFactory)

    @Test
    fun installThenInstalledSourcesReturnsTheSource() = runTest {
        val repo = newRepository(bundleDirWithFlameComics())

        repo.install(SourceInfo(sourceId = "FlameComics", name = "Flame Comics"), FLAME_COMICS_SHA256)

        assertEquals(
            listOf(SourceInfo(sourceId = "FlameComics", name = "Flame Comics")),
            repo.installedSources(),
        )
    }

    @Test
    fun installedSourcesReturnsThePinnedVersion() = runTest {
        val repo = newRepository(bundleDirWithFlameComics())

        repo.install(SourceInfo(sourceId = "FlameComics", name = "Flame Comics", version = "1.2.3"), FLAME_COMICS_SHA256)

        assertEquals(
            listOf(SourceInfo(sourceId = "FlameComics", name = "Flame Comics", version = "1.2.3")),
            repo.installedSources(),
        )
    }

    @Test
    fun fullReadPathThroughTheRepository() = runTest {
        val repo = newRepository(bundleDirWithFlameComics())
        repo.install(SourceInfo(sourceId = "FlameComics", name = "Flame Comics"), FLAME_COMICS_SHA256)

        val results = repo.search("FlameComics", "")
        assertTrue(results.isNotEmpty(), "expected search results from recorded fixtures")

        val details = repo.details("FlameComics", "57")
        assertEquals("IRL Quest", details.entry.title)

        val chapters = repo.chapters("FlameComics", "57")
        assertTrue(chapters.isNotEmpty(), "expected chapters from recorded fixtures")

        val pages = repo.pages("FlameComics", "57", chapters.first().chapterId)
        assertTrue(pages.isNotEmpty(), "expected pages from recorded fixtures")
    }

    @Test
    fun sourceIsVerifiedAndConstructedOnlyOnceAcrossCalls() = runTest {
        val calls = AtomicInteger(0)
        val repo = newRepository(
            bundleDirWithFlameComics(),
            sourceFactory = { sourceId, bundleJs, _ ->
                calls.incrementAndGet()
                PaperbackExtension(sourceId, bundleJs, ApplicationHost(http = RecordedHttp.replayClient()))
            },
        )
        repo.install(SourceInfo(sourceId = "FlameComics", name = "Flame Comics"), FLAME_COMICS_SHA256)

        repo.search("FlameComics", "")
        repo.details("FlameComics", "57")

        assertEquals(1, calls.get(), "expected the source to be resolved once and cached")
    }

    @Test
    fun unknownSourceIdThrows() = runTest {
        val repo = newRepository(bundleDirWithFlameComics())

        assertFailsWith<UnknownSourceException> { repo.search("NoSuchSource", "") }
    }

    @Test
    fun installedSourceWithoutBundleFileThrowsNamedError() = runTest {
        val repo = newRepository(Files.createTempDirectory("catalog-test"))
        repo.install(SourceInfo(sourceId = "FlameComics", name = "Flame Comics"), FLAME_COMICS_SHA256)

        assertFailsWith<MissingBundleException> { repo.search("FlameComics", "") }
    }

    @Test
    fun reinstallEvictsTheCachedSource() = runTest {
        val calls = AtomicInteger(0)
        val repo = newRepository(
            bundleDirWithFlameComics(),
            sourceFactory = { sourceId, bundleJs, _ ->
                calls.incrementAndGet()
                PaperbackExtension(sourceId, bundleJs, ApplicationHost(http = RecordedHttp.replayClient()))
            },
        )
        val info = SourceInfo(sourceId = "FlameComics", name = "Flame Comics")
        repo.install(info, FLAME_COMICS_SHA256)
        repo.search("FlameComics", "")

        repo.install(info, FLAME_COMICS_SHA256)
        repo.search("FlameComics", "")

        assertEquals(2, calls.get(), "expected reinstall to force re-verification and rebuild")
    }

    @Test
    fun setUserAgentRoundTripsAndEvictsTheCachedSource() = runTest {
        val calls = AtomicInteger(0)
        val seenUserAgents = mutableListOf<String?>()
        val db = newDb()
        val repo = newRepository(
            bundleDirWithFlameComics(),
            db = db,
            sourceFactory = { sourceId, bundleJs, userAgent ->
                calls.incrementAndGet()
                seenUserAgents += userAgent
                PaperbackExtension(sourceId, bundleJs, ApplicationHost(http = RecordedHttp.replayClient()))
            },
        )
        repo.install(SourceInfo(sourceId = "FlameComics", name = "Flame Comics"), FLAME_COMICS_SHA256)
        repo.search("FlameComics", "")

        repo.setUserAgent("FlameComics", "pinned-ua")
        repo.search("FlameComics", "")

        assertEquals(2, calls.get(), "expected setUserAgent to evict the cached source and force a rebuild")
        assertEquals(listOf(null, "pinned-ua"), seenUserAgents)
        assertEquals(
            "pinned-ua",
            db.sourcesQueries.selectInstalledSource("FlameComics").executeAsOne().user_agent,
        )
    }

    @Test
    fun setUserAgentWithUnsafeSourceIdIsRejected() = runTest {
        val repo = newRepository(bundleDirWithFlameComics())

        assertFailsWith<IllegalArgumentException> { repo.setUserAgent("../evil", "some-ua") }
    }

    @Test
    fun reinstallPreservesAPinnedUserAgent() = runTest {
        // the cf_clearance-bound UA earned by the solve flow must outlive an extension update
        val seenUserAgents = mutableListOf<String?>()
        val db = newDb()
        val repo = newRepository(
            bundleDirWithFlameComics(),
            db = db,
            sourceFactory = { sourceId, bundleJs, userAgent ->
                seenUserAgents += userAgent
                PaperbackExtension(sourceId, bundleJs, ApplicationHost(http = RecordedHttp.replayClient()))
            },
        )
        val info = SourceInfo(sourceId = "FlameComics", name = "Flame Comics", version = "1.0.0")
        repo.install(info, FLAME_COMICS_SHA256)
        repo.setUserAgent("FlameComics", "pinned-ua")
        repo.search("FlameComics", "")

        repo.install(info.copy(version = "2.0.0"), FLAME_COMICS_SHA256) // update
        repo.search("FlameComics", "")

        assertEquals("pinned-ua", seenUserAgents.last())
        assertEquals(
            "pinned-ua",
            db.sourcesQueries.selectInstalledSource("FlameComics").executeAsOne().user_agent,
        )
    }

    @Test
    fun pathTraversalSourceIdIsRejected() = runTest {
        val repo = newRepository(bundleDirWithFlameComics())

        assertFailsWith<IllegalArgumentException> {
            repo.install(SourceInfo(sourceId = "../evil", name = "Evil"), FLAME_COMICS_SHA256)
        }
        assertFailsWith<IllegalArgumentException> { repo.search("..\\evil", "") }
    }

    @Test
    fun tamperedBundleFailsVerification() = runTest {
        val dir = Files.createTempDirectory("catalog-test")
        Files.write(dir.resolve("FlameComics.index.js"), "not the real bundle".encodeToByteArray())
        val repo = newRepository(dir)
        repo.install(SourceInfo(sourceId = "FlameComics", name = "Flame Comics"), FLAME_COMICS_SHA256)

        assertFailsWith<BundleVerificationException> {
            repo.search("FlameComics", "")
        }
    }
}
