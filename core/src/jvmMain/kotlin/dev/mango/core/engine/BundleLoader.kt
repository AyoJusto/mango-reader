package dev.mango.core.engine

import java.security.MessageDigest

class BundleVerificationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// ponytail: JVM-only via MessageDigest; expect/actual when native targets land
object BundleLoader {
    const val MAX_BUNDLE_BYTES = 2 * 1024 * 1024

    /**
     * Gate every third-party bundle before it reaches the JS engine: size cap, pinned
     * sha256, and strict UTF-8. Returns the bundle source only if all three pass.
     *
     * The size cap here is defense-in-depth: the bytes are already in memory. Whatever
     * downloads bundles owns the streaming cap that aborts oversized transfers.
     */
    fun verify(bytes: ByteArray, expectedSha256: String): String {
        if (bytes.size > MAX_BUNDLE_BYTES) {
            throw BundleVerificationException(
                "bundle is ${bytes.size} bytes, max allowed is $MAX_BUNDLE_BYTES"
            )
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw BundleVerificationException(
                "bundle checksum mismatch: expected $expectedSha256, got $actual"
            )
        }
        try {
            return bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw BundleVerificationException("bundle is not valid UTF-8", e)
        }
    }
}
