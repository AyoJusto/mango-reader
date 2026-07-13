package dev.mango.app.webview

import dev.mango.core.domain.StoredCookie
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager

/**
 * The user-agent presented by the embedded browser AND pinned to any source it solves for.
 * cf_clearance is bound to the UA that earned it, so the browser and the extension's later
 * requests must send the same string — a fixed value guarantees that. Desktop Chrome matching
 * the bundled Chromium keeps Cloudflare happy.
 * ponytail: bump the Chrome major when the jcef version (Chromium) jumps.
 */
const val WebViewUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

/**
 * Owns the single process-wide JCEF (embedded Chromium) instance: lazily initialized on first
 * solve (which downloads the CEF runtime into [installDir]), reused thereafter, disposed on app
 * exit. All the embedded-browser mechanics for the Cloudflare solve live here.
 */
class JcefManager(private val installDir: Path) {
    private val initLock = Mutex()
    @Volatile
    private var app: CefApp? = null

    /** Init state for the UI, so the first-run CEF download isn't a frozen window. */
    sealed interface Progress {
        data class Downloading(val percent: Float) : Progress
        data object Extracting : Progress
        data object Ready : Progress
    }

    /** Ensures CEF is initialized (downloading it on first call). Safe to call concurrently. */
    private suspend fun ensureApp(onProgress: (Progress) -> Unit): CefApp = initLock.withLock {
        app?.let { return it }
        withContext(Dispatchers.IO) {
            val builder = CefAppBuilder()
            builder.setInstallDir(installDir.toFile())
            builder.cefSettings.windowless_rendering_enabled = false
            builder.cefSettings.user_agent = WebViewUserAgent
            builder.setProgressHandler { state, percent ->
                when (state) {
                    EnumProgress.DOWNLOADING -> onProgress(Progress.Downloading(percent))
                    EnumProgress.EXTRACTING -> onProgress(Progress.Extracting)
                    EnumProgress.INITIALIZED -> onProgress(Progress.Ready)
                    else -> Unit
                }
            }
            builder.build().also { app = it }
        }
    }

    /**
     * Opens a browser window on [url] for the user to pass the challenge. Returns the harvested
     * clearance cookies (cf_clearance and friends) for [host] once they appear, or null if the
     * user closed the window first. [onProgress] reports first-run CEF download status.
     */
    suspend fun solve(host: String, url: String, onProgress: (Progress) -> Unit): List<StoredCookie>? {
        val cefApp = ensureApp(onProgress)
        val result = CompletableDeferred<List<StoredCookie>?>()

        SwingUtilities.invokeLater {
            val client = cefApp.createClient()
            val browser = client.createBrowser(url, false, false)
            val frame = JFrame("Solve the challenge for $host — mango")
            frame.contentPane.add(browser.uiComponent, BorderLayout.CENTER)
            frame.setSize(1100, 820)
            frame.setLocationRelativeTo(null)

            fun finish(cookies: List<StoredCookie>?) {
                if (result.isCompleted) return
                result.complete(cookies)
                // dispose on the EDT; the client outlives one solve is fine, CefApp is the singleton
                frame.dispose()
                browser.close(true)
                client.dispose()
            }

            // closing the window without passing the challenge = user gave up
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent?) = finish(null)
            })

            // poll the cookie jar until Cloudflare has issued clearance for this host.
            // visitAllCookies runs the visitor ASYNCHRONOUSLY on the CEF thread — it returns
            // before any visit() fires — so completion must be driven from inside the visitor
            // (on the last cookie), never checked synchronously after the call.
            val pollTimer = Timer(2000, null)
            pollTimer.addActionListener {
                val harvested = mutableListOf<StoredCookie>()
                CefCookieManager.getGlobalManager().visitAllCookies { cookie, count, total, _ ->
                    if (isChallengeCookie(cookie.name) && hostMatches(host, cookie.domain)) {
                        harvested += cookie.toStored()
                    }
                    // last cookie of this sweep: decide with the fully-collected list
                    if (count == total - 1 && harvested.any { it.name == "cf_clearance" }) {
                        val done = harvested.toList()
                        SwingUtilities.invokeLater {
                            pollTimer.stop()
                            finish(done)
                        }
                    }
                    true
                }
            }
            pollTimer.isRepeats = true
            pollTimer.start()
            frame.isVisible = true
        }

        return result.await()
    }

    fun dispose() {
        app?.dispose()
        app = null
    }

    // cf_clearance is the pass; __cf_bm/_cfuvid/cf_chl_* ride along the way a browser sends them
    private fun isChallengeCookie(name: String): Boolean =
        name == "cf_clearance" || name.startsWith("__cf") || name.startsWith("cf_") || name == "_cfuvid"

    private fun hostMatches(host: String, domain: String): Boolean {
        val d = domain.removePrefix(".")
        return host == d || host.endsWith(".$d")
    }

    private fun CefCookie.toStored(): StoredCookie = StoredCookie(
        name = name,
        value = value,
        domain = domain ?: "",
        path = path ?: "/",
        expiresAtMillis = expires?.time,
    )
}
