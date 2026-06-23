package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.kbrowser.webview.AxNode
import xyz.kbrowser.webview.AxTreeData
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.initializeKBrowser
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Screenshot ↔ Coordinate Correspondence Test
 *
 * Goal: verify that AX Tree coordinates map correctly to screenshot pixel positions.
 *
 * Method (for each visible AX node):
 *   1. Convert document coords → viewport: vpX = centerX - scrollX
 *   2. elementFromPoint(vpX, vpY) → check if returned element is inside the AX node
 *   3. Get "visible color" at (vpX, vpY) via JS (walk up alpha chain)
 *   4. Read pixel color from screenshot at (vpX, vpY)
 *   5. Compare JS visible color vs screenshot pixel → confirms coordinate alignment
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("═══════════════════════════════════════════════════════════")
    println("   Screenshot ↔ Coordinate Correspondence Test (v2)")
    println("═══════════════════════════════════════════════════════════")

    val testSites = listOf(
        TestSite("https://example.com", "Example.com (simple page)"),
        TestSite("https://en.wikipedia.org/wiki/Kotlin_(programming_language)", "Wikipedia (complex layout)"),
        TestSite("https://news.ycombinator.com/", "Hacker News (classic web)")
    )

    val allResults = mutableListOf<SiteResult>()

    runBlocking {
        try {
            val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
            KBrowser.initializeConfig(storageDir, useOsr = true)
            initializeKBrowser()
            println("[Test] KBCefApp initialized")
            delay(3000)

            for (site in testSites) {
                println("\n${"─".repeat(60)}")
                println("Testing: ${site.label}")
                println("URL: ${site.url}")
                println("─".repeat(60))

                val result = testSite(site)
                allResults.add(result)
            }

            printFinalReport(allResults)

        } catch (e: Exception) {
            println("❌ Fatal error: ${e.message}")
            e.printStackTrace()
        } finally {
            println("\n[Test] Shutting down KBrowser...")
            KBrowser.shutdown()
            exitProcess(0)
        }
    }
}

data class TestSite(val url: String, val label: String)

data class SiteResult(
    val label: String,
    val url: String,
    val loaded: Boolean,
    val screenshotOk: Boolean,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
    val axTreeNodes: Int,
    val visibleNodes: Int,
    val nodesChecked: Int,
    val efpInsideNode: Int,       // elementFromPoint returns element inside this AX node
    val efpMatchTag: Int,         // elementFromPoint returns same tag
    val colorMatch: Int,          // dist < 30
    val colorClose: Int,          // dist < 80
    val colorMismatch: Int,       // dist >= 80
    val colorTransparent: Int,    // skipped due to transparent
    val details: List<String>
)

/**
 * Test a single website.
 */
private suspend fun testSite(site: TestSite): SiteResult {
    val details = mutableListOf<String>()
    var screenshotWidth = 0
    var screenshotHeight = 0
    var axTreeNodes = 0
    var visibleNodes = 0
    var nodesChecked = 0
    var efpInsideNode = 0
    var efpMatchTag = 0
    var colorMatch = 0
    var colorClose = 0
    var colorMismatch = 0
    var colorTransparent = 0

    val page = try {
        KBrowser.newPage(site.url)
    } catch (e: Exception) {
        println("  ❌ Failed to create page: ${e.message}")
        return SiteResult(site.label, site.url, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, details)
    }

    println("  Waiting for page load...")
    delay(8000)

    // Step 1: AX tree + screenshot (as close together as possible)
    println("  Getting AX tree...")
    val tree: AxTreeData
    try {
        tree = page.getRawAxTree()
        axTreeNodes = tree.nodes.size
        visibleNodes = tree.nodes.count { it.isVisible && it.width > 0 && it.height > 0 }
        println("  AX Tree: $axTreeNodes total, $visibleNodes visible")
        println("  Viewport: ${tree.innerWidth}×${tree.innerHeight}, scroll=(${tree.scrollX},${tree.scrollY}), DPR=${tree.devicePixelRatio}")
    } catch (e: Exception) {
        println("  ❌ AX tree failed: ${e.message}")
        page.close()
        return SiteResult(site.label, site.url, true, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, details)
    }

    println("  Taking screenshot...")
    val screenshotBytes = page.screenshot()
    if (screenshotBytes == null || screenshotBytes.isEmpty()) {
        println("  ❌ Screenshot failed")
        page.close()
        return SiteResult(site.label, site.url, true, false, 0, 0, axTreeNodes, visibleNodes, 0, 0, 0, 0, 0, 0, 0, details)
    }

    val image: BufferedImage = ImageIO.read(ByteArrayInputStream(screenshotBytes))
    screenshotWidth = image.width
    screenshotHeight = image.height
    println("  Screenshot: ${image.width}×${image.height}, ${screenshotBytes.size} bytes")

    val safeName = site.label.replace(Regex("[^a-zA-Z0-9]"), "_")
    val screenshotFile = File("desktopApp/build/screenshot_${safeName}.png")
    screenshotFile.parentFile?.mkdirs()
    ImageIO.write(image, "png", screenshotFile)

    val scrollX = tree.scrollX
    val scrollY = tree.scrollY
    val viewW = tree.innerWidth
    val viewH = tree.innerHeight

    // Filter candidates: visible, has size, within viewport
    val candidates = tree.nodes.filter { node ->
        node.isVisible && node.width > 10 && node.height > 10
    }.filter { node ->
        val vpX = node.centerX - scrollX
        val vpY = node.centerY - scrollY
        vpX in 10 until (viewW - 10) && vpY in 10 until (viewH - 10)
    }

    println("  Candidates in viewport: ${candidates.size}")

    // Sample up to 30 nodes
    val sampleNodes = candidates.take(30)

    for (node in sampleNodes) {
        val vpX = node.centerX - scrollX
        val vpY = node.centerY - scrollY
        if (vpX !in 0 until image.width || vpY !in 0 until image.height) continue
        nodesChecked++

        // Read screenshot pixel
        val pixelRgb = image.getRGB(vpX, vpY) and 0x00FFFFFF
        val pixelR = (pixelRgb shr 16) and 0xFF
        val pixelG = (pixelRgb shr 8) and 0xFF
        val pixelB = pixelRgb and 0xFF

        // Combined JS: get visible color + elementFromPoint info + node identity check
        val selectorEsc = node.selector.replace("\\", "\\\\").replace("\"", "\\\"")
        val combinedJs = """
            (function() {
                // 1. Visible color at point: walk up DOM until non-transparent bg
                var vx = $vpX, vy = $vpY;
                var el = document.elementFromPoint(vx, vy);
                var topTag = el ? el.tagName.toLowerCase() : '';
                var topId = el ? (el.id || '') : '';

                // 2. Walk up from elementFromPoint result to get visible color
                var colorEl = el;
                var bgColor = null;
                for (var i = 0; i < 20 && colorEl; i++) {
                    var bg = window.getComputedStyle(colorEl).backgroundColor;
                    var m = bg.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+))?\s*\)/);
                    if (m) {
                        var a = m[4] !== undefined ? parseFloat(m[4]) : 1;
                        if (a > 0.1) {
                            bgColor = [parseInt(m[1]), parseInt(m[2]), parseInt(m[3])];
                            break;
                        }
                    }
                    colorEl = colorEl.parentElement;
                }

                // 3. Check if elementFromPoint result is inside our target node
                var targetSel = "$selectorEsc";
                var insideTarget = false;
                var targetTag = '';
                var targetId = '';
                if (targetSel && el) {
                    try {
                        var targetEl = document.querySelector(targetSel);
                        if (targetEl) {
                            targetTag = targetEl.tagName.toLowerCase();
                            targetId = targetEl.id || '';
                            insideTarget = targetEl.contains(el) || el.contains(targetEl);
                        }
                    } catch(e) {}
                }

                return JSON.stringify({
                    topTag: topTag, topId: topId,
                    bg: bgColor,
                    insideTarget: insideTarget,
                    targetTag: targetTag, targetId: targetId
                });
            })()
        """.trimIndent()

        val jsResult = try {
            page.evaluateJavascript(combinedJs).trim()
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }

        // Parse JS result
        val parsed = try {
            Json.parseToJsonElement(jsResult).jsonObject
        } catch (e: Exception) {
            null
        }

        if (parsed != null) {
            val topTag = parsed["topTag"]?.jsonPrimitive?.content ?: ""
            val topId = parsed["topId"]?.jsonPrimitive?.content ?: ""
            val insideTarget = parsed["insideTarget"]?.jsonPrimitive?.content?.toBoolean() ?: false
            val targetTag = parsed["targetTag"]?.jsonPrimitive?.content ?: ""
            val targetId = parsed["targetId"]?.jsonPrimitive?.content ?: ""

            // elementFromPoint check
            if (insideTarget) efpInsideNode++
            if (topTag == node.tagName || topTag == targetTag) efpMatchTag++

            // Color check
            val bgArray = parsed["bg"]
            if (bgArray != null && bgArray.toString() != "null") {
                val parts = try {
                    val arr = bgArray.toString().removeSurrounding("[", "]").split(",")
                    arr.map { it.trim().toInt() }
                } catch (e: Exception) { null }

                if (parts != null && parts.size >= 3) {
                    val dist = colorDistance(pixelR, pixelG, pixelB, parts[0], parts[1], parts[2])
                    when {
                        dist < 30 -> {
                            colorMatch++
                            details.add("  ✅ ${node.tagName}${if (node.id.isNotEmpty()) "#${node.id}" else ""} (${node.role}) @($vpX,$vpY): pix=($pixelR,$pixelG,$pixelB) js=(${parts[0]},${parts[1]},${parts[2]}) d=${"%.0f".format(dist)} efp=$topTag${if (topId.isNotEmpty()) "#$topId" else ""} inside=${insideTarget}")
                        }
                        dist < 80 -> {
                            colorClose++
                            details.add("  🔶 ${node.tagName}${if (node.id.isNotEmpty()) "#${node.id}" else ""} (${node.role}) @($vpX,$vpY): pix=($pixelR,$pixelG,$pixelB) js=(${parts[0]},${parts[1]},${parts[2]}) d=${"%.0f".format(dist)} efp=$topTag${if (topId.isNotEmpty()) "#$topId" else ""} inside=${insideTarget}")
                        }
                        else -> {
                            colorMismatch++
                            details.add("  ❌ ${node.tagName}${if (node.id.isNotEmpty()) "#${node.id}" else ""} (${node.role}) @($vpX,$vpY): pix=($pixelR,$pixelG,$pixelB) js=(${parts[0]},${parts[1]},${parts[2]}) d=${"%.0f".format(dist)} efp=$topTag${if (topId.isNotEmpty()) "#$topId" else ""} inside=${insideTarget}")
                        }
                    }
                } else {
                    colorTransparent++
                }
            } else {
                colorTransparent++
                details.add("  ⚠️ ${node.tagName}${if (node.id.isNotEmpty()) "#${node.id}" else ""} (${node.role}) @($vpX,$vpY): pix=($pixelR,$pixelG,$pixelB) bg=transparent/null efp=$topTag inside=${insideTarget}")
            }
        } else {
            details.add("  ⚠️ JS parse error for ${node.tagName} @($vpX,$vpY): $jsResult")
            colorTransparent++
        }
    }

    println("  Checked $nodesChecked nodes:")
    println("    efpInside=$efpInsideNode, efpMatchTag=$efpMatchTag")
    println("    colorMatch=$colorMatch, close=$colorClose, mismatch=$colorMismatch, transparent=$colorTransparent")

    page.close()
    delay(500)

    return SiteResult(
        label = site.label, url = site.url, loaded = true, screenshotOk = true,
        screenshotWidth = screenshotWidth, screenshotHeight = screenshotHeight,
        axTreeNodes = axTreeNodes, visibleNodes = visibleNodes,
        nodesChecked = nodesChecked,
        efpInsideNode = efpInsideNode, efpMatchTag = efpMatchTag,
        colorMatch = colorMatch, colorClose = colorClose, colorMismatch = colorMismatch,
        colorTransparent = colorTransparent,
        details = details
    )
}

private fun colorDistance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
    return sqrt(((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble())
}

private fun printFinalReport(results: List<SiteResult>) {
    println("\n")
    println("╔══════════════════════════════════════════════════════════════════════╗")
    println("║        Screenshot ↔ Coordinate — Final Report                      ║")
    println("╚══════════════════════════════════════════════════════════════════════╝")

    for (r in results) {
        println("\n┌─ ${r.label}")
        println("│  URL: ${r.url}")
        println("│  Page: ${r.loaded}, Screenshot: ${r.screenshotOk} (${r.screenshotWidth}×${r.screenshotHeight})")
        println("│  AX Tree: ${r.axTreeNodes} nodes (${r.visibleNodes} visible)")
        println("│  Checked: ${r.nodesChecked} nodes")

        if (r.nodesChecked > 0) {
            val n = r.nodesChecked
            val efpInPct = "%.1f".format(r.efpInsideNode * 100.0 / n)
            val efpTagPct = "%.1f".format(r.efpMatchTag * 100.0 / n)
            val colorExactPct = "%.1f".format(r.colorMatch * 100.0 / n)
            val colorClosePct = "%.1f".format((r.colorMatch + r.colorClose) * 100.0 / n)
            val colorMissPct = "%.1f".format(r.colorMismatch * 100.0 / n)

            println("│")
            println("│  ── elementFromPoint ──")
            println("│  Inside target node:   ${r.efpInsideNode}/$n ($efpInPct%)")
            println("│  Same tag match:       ${r.efpMatchTag}/$n ($efpTagPct%)")
            println("│")
            println("│  ── Color (screenshot pixel vs JS visible bg) ──")
            println("│  Exact match (d<30):   ${r.colorMatch}/$n ($colorExactPct%)")
            println("│  Close match (d<80):   ${r.colorMatch + r.colorClose}/$n ($colorClosePct%)")
            println("│  Mismatch (d≥80):      ${r.colorMismatch}/$n ($colorMissPct%)")
            println("│  Transparent/skip:     ${r.colorTransparent}/$n")

            // Verdict
            val efpOk = r.efpInsideNode * 100.0 / n >= 60
            val colorOk = (r.colorMatch + r.colorClose) * 100.0 / (n - r.colorTransparent).coerceAtLeast(1) >= 60
            val verdict = when {
                efpOk && colorOk -> "✅ COORDINATES RELIABLE — screenshot pixels align with AX tree positions"
                efpOk && !colorOk -> "🔶 COORDINATES OK structurally, color deviation (gradients/text/borders)"
                !efpOk && colorOk -> "🔶 COLOR OK but elementFromPoint mismatch — check container logic"
                else -> "❌ COORDINATE ISSUES — possible offset or DPR problem"
            }
            println("│")
            println("│  VERDICT: $verdict")
        } else {
            println("│  (No nodes checked)")
        }

        if (r.details.isNotEmpty()) {
            println("│")
            println("│  Details:")
            r.details.forEach { println("│  $it") }
        }
        println("└${"─".repeat(68)}")
    }

    // Overall summary
    println()
    val totalN = results.sumOf { it.nodesChecked }
    val totalEfpIn = results.sumOf { it.efpInsideNode }
    val totalEfpTag = results.sumOf { it.efpMatchTag }
    val totalCM = results.sumOf { it.colorMatch }
    val totalCC = results.sumOf { it.colorClose }
    val totalCMis = results.sumOf { it.colorMismatch }
    val totalTrans = results.sumOf { it.colorTransparent }
    val totalColorN = totalN - totalTrans

    if (totalN > 0) {
        println("═══ OVERALL ═══")
        println("Total checked: $totalN (color-valid: $totalColorN)")
        println("elementFromPoint inside target: ${"%.1f".format(totalEfpIn * 100.0 / totalN)}%")
        println("elementFromPoint same tag:     ${"%.1f".format(totalEfpTag * 100.0 / totalN)}%")
        if (totalColorN > 0) {
            println("Color exact match (d<30):      ${"%.1f".format(totalCM * 100.0 / totalColorN)}%")
            println("Color close match (d<80):      ${"%.1f".format((totalCM + totalCC) * 100.0 / totalColorN)}%")
            println("Color mismatch (d≥80):         ${"%.1f".format(totalCMis * 100.0 / totalColorN)}%")
        }
        println()

        val efpRate = totalEfpIn * 100.0 / totalN
        val colorRate = if (totalColorN > 0) (totalCM + totalCC) * 100.0 / totalColorN else 0.0

        when {
            efpRate >= 60 && colorRate >= 60 -> {
                println("✅ CONCLUSION: Screenshots CAN reliably determine element coordinates.")
                println("   AX Tree coordinates correctly correspond to screenshot pixel positions.")
            }
            efpRate >= 60 -> {
                println("🔶 CONCLUSION: Coordinates are STRUCTURALLY CORRECT (elementFromPoint confirms)")
                println("   Pixel-level color matching shows deviations (text overlay, gradients, borders).")
                println("   The coordinate system is reliable for click/hover operations.")
            }
            efpRate >= 40 -> {
                println("🔶 CONCLUSION: Coordinates are PARTIALLY CORRECT.")
                println("   Some elements align, others don't. Container vs leaf element distinction needed.")
            }
            else -> {
                println("❌ CONCLUSION: Significant coordinate accuracy issues.")
            }
        }
    } else {
        println("⚠️ No data collected.")
    }
    println("═".repeat(70))
}
