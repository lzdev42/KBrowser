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
 * In Remote mode the native peer is created asynchronously inside the cef_server process and
 * can only be observed by reflectively polling isNativeBrowserCreated(). Previously loadUrl
 * waits, CDP dialog attachment, AX fetching, and Debug init each duplicated their own polling
 * implementation and spun threads polling the same browser. This latch caches the probe
 * outcome: the first caller triggers one background probe, the rest simply await its result.
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
     * true  — native peer is ready, or optimistically allowed when it cannot be probed
     *         (non-Remote mode has no such method, which is the normal case);
     * false — probing is available but timed out; the caller decides how to degrade.
     */
    private fun probe(browser: CefBrowser): Boolean {
        val method = try {
            browser.javaClass.getMethod("isNativeBrowserCreated").also { it.isAccessible = true }
        } catch (e: NoSuchMethodException) {
            return true
        } catch (e: Exception) {
            return true
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val ready = try {
                method.invoke(browser) as? Boolean ?: return true
            } catch (e: Exception) {
                return true
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
