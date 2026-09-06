package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Per-[CefBrowser] cache of native-peer readiness probe results.
 *
 * In Remote mode (OSR) the native peer is created asynchronously inside the cef_server process
 * and can only be observed by reflectively polling isNativeBrowserCreated(). In local mode
 * (non-OSR) that method does not exist; instead getDevToolsClient() returns null until the
 * native browser exists (it gates on the internal isPending_ flag set by notifyBrowserCreated()),
 * and polling it avoids issuing loadUrl/loadHtml before creation, where JCEF silently drops them.
 *
 * This latch caches the probe outcome: the first caller triggers one background probe, the
 * rest simply await its result.
 */
internal object CefNativeReadyLatch {

    private val results = ConcurrentHashMap<CefBrowser, CompletableFuture<Boolean>>()

    private val poller = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KB-NativeReady-Poller").apply { isDaemon = true }
    }

    fun await(browser: CefBrowser): Future<Boolean> =
        results.computeIfAbsent(browser) {
            CompletableFuture.supplyAsync({ probe(browser) }, poller)
        }

    fun awaitBlocking(browser: CefBrowser, timeoutSec: Long): Boolean = try {
        await(browser).get(timeoutSec, TimeUnit.SECONDS)
    } catch (_: Exception) {
        false
    }

    fun forget(browser: CefBrowser) {
        results.remove(browser)
    }

    /**
     * Return semantics:
     * true  — native browser is ready, or optimistically allowed when no creation
     *         signal can be probed at all;
     * false — a signal exists but timed out; the caller decides how to degrade.
     */
    private fun probe(browser: CefBrowser): Boolean {
        // Remote mode (OSR): public JBR method reporting native-peer creation.
        val createdMethod = try {
            browser.javaClass.getMethod("isNativeBrowserCreated").apply { isAccessible = true }
        } catch (_: Exception) {
            null
        }

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val ready = if (createdMethod != null) {
                try {
                    createdMethod.invoke(browser) as? Boolean ?: true
                } catch (_: Exception) {
                    true
                }
            } else {
                // Local mode (non-OSR): non-null only after the native browser exists.
                try {
                    browser.devToolsClient != null
                } catch (_: Exception) {
                    true
                }
            }
            if (ready) return true
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
        }
        return false
    }
}
