package dev.mango.core.engine

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

const val FLAME_COMICS_FIXTURE = "FlameComics.index.js"
const val FLAME_COMICS_SHA256 = "7bc0747ee748f812b9b42d585b83e6da0f9c45c6467a0044b22ad77ae144629a"

internal fun readFixture(name: String): ByteArray =
    checkNotNull(BundleLoaderTest::class.java.getResourceAsStream("/fixtures/$name")) {
        "missing fixture $name"
    }.readBytes()

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
