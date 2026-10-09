package xyz.kbrowser.jcef

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Anti-fingerprinting helpers applied to a CEF browser over CDP.
 *
 * The real Chromium version is read reflectively from
 * `com.jetbrains.cef.JCefAppConfig.getVersionDetails()` so the spoofed
 * User-Agent / Sec-CH-UA metadata matches the actual bundled engine, then:
 * 1. `Emulation.setUserAgentOverride` replaces the UA plus client-hint
 *    metadata (brands contain only "Chromium" + a GREASE placeholder —
 *    never "Google Chrome").
 * 2. `Page.addScriptToEvaluateOnNewDocument` installs a standard
 *    `window.chrome` object before any page script runs.
 * 3. `Page.addScriptToEvaluateOnNewDocument` polls to hide the JCEF bridge
 *    globals (`_kbq_*` / `cefQuery*`) by redefining them as non-enumerable.
 *
 * All CDP calls block, so [apply] must be invoked on a background thread,
 * never on the EDT. The native peer readiness is awaited internally via
 * [CefNativeReadyLatch] before touching the DevTools client.
 */
object KBCefAntiFingerprint {

    private const val NATIVE_READY_TIMEOUT_SEC = 15L
    private const val CDP_CALL_TIMEOUT_SEC = 5L
    private const val FALLBACK_MAJOR = "130"
    private const val FALLBACK_FULL = "130.0.0.0"

    private data class ChromiumVersion(val major: String, val full: String)

    /**
     * Applies the anti-fingerprinting overrides to [browser].
     *
     * Each CDP call is wrapped in try-catch with a 5s timeout; a failure in
     * one step only logs a message and does not abort the remaining steps.
     */
    fun apply(browser: CefBrowser) {
        // Mirror KBCefAxTreeFetcher: in Remote (OSR) mode the native peer is
        // created asynchronously, and the DevTools client stays "closed"
        // until then, so every CDP call would fail immediately.
        if (!CefNativeReadyLatch.awaitBlocking(browser, NATIVE_READY_TIMEOUT_SEC)) {
            println("[KBCefAntiFingerprint] Native browser not ready, skipping anti-fingerprint")
            return
        }

        val devTools = browser.devToolsClient
        if (devTools == null || devTools.isClosed) {
            println("[KBCefAntiFingerprint] DevTools client not available, skipping anti-fingerprint")
            return
        }

        val ver = readChromiumVersion()
        val userAgent = buildUserAgent(ver.major)
        val acceptLang = buildAcceptLanguage()
        val platform = buildPlatform()

        overrideUserAgent(devTools, ver, userAgent, acceptLang, platform)
        injectChromeObject(devTools)
        hideBridgeFunctions(devTools)
    }

    // ------------------------------------------------------------------ //
    // Chromium version discovery (reflection with hardcoded fallback)    //
    // ------------------------------------------------------------------ //

    /**
     * Reads the Chromium version from the JBR/JCEF bundle.
     *
     * Verified against `jbrsdk_jcef-25.0.4.1`: `JCefAppConfig.getVersionDetails()`
     * is a static method returning `JCefVersionDetails`; that class exposes a
     * public field `chromiumVersion` (a `ChromiumVersion` object with public
     * int fields `major/minor/build/patch`) — there is no
     * `getChromiumVersion()` getter. Both shapes are probed so older/newer JBR
     * builds keep working, and only public members are used (the `jcef` module
     * does not open `com.jetbrains.cef` to reflection).
     */
    private fun readChromiumVersion(): ChromiumVersion {
        try {
            val configClass = Class.forName("com.jetbrains.cef.JCefAppConfig")
            val getVersionDetails = configClass.getMethod("getVersionDetails")
            val details = getVersionDetails.invoke(null) ?: return fallbackVersion()

            // Shape 1: details itself is a version string.
            if (details is String) {
                return parseVersionString(details)
            }

            // Shape 2: a getChromiumVersion() getter returning a String.
            try {
                val getter = details.javaClass.getMethod("getChromiumVersion")
                val versionStr = getter.invoke(details) as? String
                if (!versionStr.isNullOrBlank()) {
                    return parseVersionString(versionStr)
                }
            } catch (_: NoSuchMethodException) {
                // Expected on current JBR builds — fall through to the field.
            } catch (_: Exception) {
                // Reflection failure on the getter — fall through to the field.
            }

            // Shape 3 (current JBR): public field chromiumVersion holding either
            // a String or an object with major/minor/build/patch int fields.
            try {
                val field = details.javaClass.getField("chromiumVersion")
                val value = field.get(details)
                if (value is String) {
                    return parseVersionString(value)
                }
                if (value != null) {
                    val valueClass = value.javaClass
                    try {
                        val major = (valueClass.getField("major").get(value) as Number).toInt()
                        val minor = (valueClass.getField("minor").get(value) as Number).toInt()
                        val build = (valueClass.getField("build").get(value) as Number).toInt()
                        val patch = (valueClass.getField("patch").get(value) as Number).toInt()
                        return ChromiumVersion(major.toString(), "$major.$minor.$build.$patch")
                    } catch (_: Exception) {
                        // Not the structured shape — ignore and fall back.
                    }
                }
            } catch (_: Exception) {
                // No chromiumVersion field — fall back.
            }
        } catch (e: Exception) {
            println("[KBCefAntiFingerprint] Failed to read Chromium version: ${e.message}")
        }
        return fallbackVersion()
    }

    private fun parseVersionString(version: String): ChromiumVersion {
        val full = version.trim()
        val major = full.substringBefore(".")
        return if (full.isNotEmpty() && major.isNotEmpty() && major.all { it.isDigit() }) {
            ChromiumVersion(major, full)
        } else {
            fallbackVersion()
        }
    }

    private fun fallbackVersion(): ChromiumVersion = ChromiumVersion(FALLBACK_MAJOR, FALLBACK_FULL)

    // ------------------------------------------------------------------ //
    // UA / client-hint construction                                       //
    // ------------------------------------------------------------------ //

    private fun buildUserAgent(major: String): String {
        val osName = System.getProperty("os.name").lowercase()
        val platform = when {
            osName.contains("mac") -> "Macintosh; Intel Mac OS X 10_15_7"
            osName.contains("win") -> "Windows NT 10.0; Win64; x64"
            else -> "X11; Linux x86_64"
        }
        return "Mozilla/5.0 ($platform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    /**
     * Sec-CH-UA brands: GREASE placeholder + "Chromium" only (no "Google Chrome").
     * The GREASE brand/version strings rotate across Chromium releases; the
     * fixed values below are a reasonable approximation.
     */
    private fun buildBrands(major: String): String = buildBrandArray(
        "Not.A/Brand" to "8",
        "Chromium" to major
    )

    private fun buildFullVersionList(full: String): String = buildBrandArray(
        "Not.A/Brand" to "8.0.0.0",
        "Chromium" to full
    )

    private fun buildBrandArray(vararg pairs: Pair<String, String>): String {
        val items = pairs.joinToString(",") { (brand, version) ->
            """{"brand":${Json.encodeToString(JsonPrimitive(brand))},"version":${Json.encodeToString(JsonPrimitive(version))}}"""
        }
        return "[$items]"
    }

    private fun buildAcceptLanguage(): String {
        val locale = Locale.getDefault()
        val lang = locale.language  // "zh", "en" etc.
        val country = locale.country // "CN", "US" etc.
        return if (country.isNotEmpty()) {
            "$lang-$country,$lang;q=0.9,en;q=0.8"
        } else {
            "$lang,en;q=0.9"
        }
    }

    private fun buildPlatform(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") -> "macOS"
            osName.contains("win") -> "Windows"
            else -> "Linux"
        }
    }

    // ------------------------------------------------------------------ //
    // CDP steps                                                           //
    // ------------------------------------------------------------------ //

    private fun overrideUserAgent(
        devTools: CefDevToolsClient,
        ver: ChromiumVersion,
        userAgent: String,
        acceptLang: String,
        platform: String
    ) {
        val uaJson = Json.encodeToString(JsonPrimitive(userAgent))
        val langJson = Json.encodeToString(JsonPrimitive(acceptLang))
        val fullJson = Json.encodeToString(JsonPrimitive(ver.full))
        val platJson = Json.encodeToString(JsonPrimitive(platform))
        val brandsJson = buildBrands(ver.major)
        val fullVersionListJson = buildFullVersionList(ver.full)

        val params = """
            {
              "userAgent": $uaJson,
              "acceptLanguage": $langJson,
              "userAgentMetadata": {
                "brands": $brandsJson,
                "fullVersionList": $fullVersionListJson,
                "fullVersion": $fullJson,
                "platform": $platJson,
                "platformVersion": "",
                "architecture": "x86",
                "bitness": "64",
                "model": "",
                "mobile": false,
                "wow64": false
              }
            }
        """.trimIndent()

        try {
            devTools.executeDevToolsMethod("Emulation.setUserAgentOverride", params)
                .get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            println("[KBCefAntiFingerprint] setUserAgentOverride failed: ${e.message}")
        }
    }

    /** Injects a standard `window.chrome` object before any page script runs. */
    private fun injectChromeObject(devTools: CefDevToolsClient) {
        val script = """
            (function() {
                if (window.chrome) return;
                window.chrome = {
                    app: {
                        isInstalled: false,
                        InstallState: {DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'},
                        RunningState: {CANNOT_RUN:'cannot_run',READY_TO_RUN:'ready_to_run',RUNNING:'running'},
                        getDetails: function(){return null},
                        getIsInstalled: function(){return false}
                    },
                    runtime: {
                        connect: function(){return {}},
                        sendMessage: function(){},
                        id: undefined
                    },
                    csi: function(){return {onloadT:Date.now(),startE:Date.now(),tran:15}},
                    loadTimes: function(){return {commitLoadTime:Date.now()/1000,connectionInfo:'h2',finishDocumentLoadTime:Date.now()/1000,finishLoadTime:Date.now()/1000,firstPaintAfterLoadTime:0,firstPaintTime:Date.now()/1000,navigationType:'Other',npnNegotiatedProtocol:'h2',requestTime:Date.now()/1000,startLoadTime:Date.now()/1000,wasAlternateProtocolAvailable:false,wasFetchedViaSPDY:true,wasNpnNegotiated:true}}
                };
            })();
        """.trimIndent()
        injectScriptOnNewDocument(devTools, script, "window.chrome")
    }

    /** Polls to hide the JCEF bridge globals (`_kbq_*` / `cefQuery*`) as non-enumerable. */
    private fun hideBridgeFunctions(devTools: CefDevToolsClient) {
        val script = """
            (function() {
                var hideBridge = function() {
                    try {
                        Object.getOwnPropertyNames(window).forEach(function(name) {
                            if (name.indexOf('_kbq_') === 0 || name.indexOf('cefQuery') === 0) {
                                try {
                                    var val = window[name];
                                    if (val !== undefined) {
                                        Object.defineProperty(window, name, {
                                            enumerable: false,
                                            configurable: true,
                                            writable: true,
                                            value: val
                                        });
                                    }
                                } catch(e) {}
                            }
                        });
                    } catch(e) {}
                };
                hideBridge();
                var iv = setInterval(hideBridge, 50);
                setTimeout(function() { clearInterval(iv); }, 5000);
            })();
        """.trimIndent()
        injectScriptOnNewDocument(devTools, script, "bridge-hiding")
    }

    private fun injectScriptOnNewDocument(devTools: CefDevToolsClient, script: String, label: String) {
        val params = """{"source":${Json.encodeToString(JsonPrimitive(script))}}"""
        try {
            devTools.executeDevToolsMethod("Page.addScriptToEvaluateOnNewDocument", params)
                .get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            println("[KBCefAntiFingerprint] $label injection failed: ${e.message}")
        }
    }
}
