package xyz.kbrowser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.initializeKBrowser
import java.io.File
import kotlin.system.exitProcess

/**
 * File upload automated test using CDP DOM.setFileInputFiles.
 *
 * Tests:
 * A) Visible input[type=file] — uploadFile(refid, paths)
 * B) Hidden input[type=file] — uploadFileBySelector("#hiddenInput", paths)
 * C) Multiple file upload — uploadFile(refid, [path1, path2])
 * D) Hidden input via button trigger — uploadFileBySelector for the hidden input
 *
 * Run: ./gradlew :desktopApp:runFileUploadTest
 */
fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    println("====== KBrowser File Upload Test (CDP DOM.setFileInputFiles) ======")

    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    KBrowser.initializeConfig(storageDir)
    runBlocking { initializeKBrowser() }

    // Create temporary test files
    val testFileA = File.createTempFile("upload_test_a", ".txt").apply {
        writeText("Hello from KBrowser upload test A!")
        deleteOnExit()
    }
    val testFileB = File.createTempFile("upload_test_b", ".txt").apply {
        writeText("Hello from KBrowser upload test B!")
        deleteOnExit()
    }
    val testFileC1 = File.createTempFile("upload_test_c1", ".txt").apply {
        writeText("Multi upload file 1")
        deleteOnExit()
    }
    val testFileC2 = File.createTempFile("upload_test_c2", ".txt").apply {
        writeText("Multi upload file 2")
        deleteOnExit()
    }

    println("[INFO] Test files created:")
    println("  A: ${testFileA.absolutePath} (${testFileA.length()} bytes)")
    println("  B: ${testFileB.absolutePath} (${testFileB.length()} bytes)")
    println("  C1: ${testFileC1.absolutePath} (${testFileC1.length()} bytes)")
    println("  C2: ${testFileC2.absolutePath} (${testFileC2.length()} bytes)")

    // Locate the test HTML file
    val htmlFile = File("desktopApp/src/test/resources/file_upload_test.html")
    if (!htmlFile.exists()) {
        println("[ERROR] Test HTML not found: ${htmlFile.absolutePath}")
        exitProcess(1)
    }
    val htmlUrl = "file://${htmlFile.absolutePath}"
    println("[INFO] Loading test page: $htmlUrl")

    runBlocking {
        var passed = 0
        var failed = 0

        try {
            val page = KBrowser.newHeadlessTab()
            page.loadUrl(htmlUrl) // suspend，返回时加载完成

            println("\n[INFO] Page loaded: ${page.webView.currentUrl.value}")

            // Get AX tree to find elements
            val tree = page.snapshot().rawTree
            println("[INFO] AX tree nodes: ${tree.nodes.size}")

            // Print relevant nodes
            tree.nodes.forEach { node ->
                if (node.id.isNotEmpty()) {
                    println("  refid=${node.refid} tag=${node.tagName} id=${node.id} " +
                        "visible=${node.isVisible} selector=${node.selector}")
                }
            }

            // ========== Test A: Visible file input (refid) ==========
            println("\n====== Test A: Visible file input (via refid) ======")
            try {
                val visibleInput = tree.nodes.find { it.id == "visibleInput" }
                if (visibleInput != null) {
                    println("[A] Found visible input: refid=${visibleInput.refid}, selector=${visibleInput.selector}")
                    page.uploadFile(visibleInput.refid, listOf(testFileA.absolutePath))
                    delay(1000)
                    val result = page.evaluateJavascript("document.getElementById('visibleResult').textContent")
                    println("[A] Page result: $result")
                    if (result.contains("upload_test_a") && result.contains("Selected")) {
                        println("[A] PASSED")
                        passed++
                    } else {
                        println("[A] FAILED - unexpected result: $result")
                        failed++
                    }
                } else {
                    println("[A] FAILED - visible input not found in AX tree")
                    failed++
                }
            } catch (e: Exception) {
                println("[A] FAILED with exception: ${e.message}")
                e.printStackTrace()
                failed++
            }

            // ========== Test B: Hidden file input (via CSS selector) ==========
            println("\n====== Test B: Hidden input (via CSS selector) ======")
            try {
                // Hidden input is NOT in AX tree, use CSS selector directly
                println("[B] Using uploadFileBySelector('#hiddenInput')")
                page.uploadFileBySelector("#hiddenInput", listOf(testFileB.absolutePath))
                delay(1000)
                val result = page.evaluateJavascript("document.getElementById('hiddenResult').textContent")
                println("[B] Page result: $result")
                if (result.contains("upload_test_b") && result.contains("Uploaded")) {
                    println("[B] PASSED")
                    passed++
                } else {
                    println("[B] FAILED - unexpected result: $result")
                    failed++
                }
            } catch (e: Exception) {
                println("[B] FAILED with exception: ${e.message}")
                e.printStackTrace()
                failed++
            }

            // ========== Test C: Multiple file upload ==========
            println("\n====== Test C: Multiple file upload ======")
            try {
                val multiInput = tree.nodes.find { it.id == "multiInput" }
                if (multiInput != null) {
                    println("[C] Found multi input: refid=${multiInput.refid}, selector=${multiInput.selector}")
                    page.uploadFile(multiInput.refid, listOf(testFileC1.absolutePath, testFileC2.absolutePath))
                    delay(1000)
                    val result = page.evaluateJavascript("document.getElementById('multiResult').textContent")
                    println("[C] Page result: $result")
                    if (result.contains("2") && result.contains("Files")) {
                        println("[C] PASSED")
                        passed++
                    } else {
                        println("[C] FAILED - unexpected result: $result")
                        failed++
                    }
                } else {
                    println("[C] FAILED - multi input not found in AX tree")
                    failed++
                }
            } catch (e: Exception) {
                println("[C] FAILED with exception: ${e.message}")
                e.printStackTrace()
                failed++
            }

            // ========== Test D: Verify JS sees the files (DataTransfer check) ==========
            println("\n====== Test D: Verify file list content via JS ======")
            try {
                // Upload again via selector and verify JS can read the File objects
                page.uploadFileBySelector("#visibleInput", listOf(testFileA.absolutePath))
                delay(500)
                val fileCount = page.evaluateJavascript(
                    "document.getElementById('visibleInput').files.length"
                )
                val fileName = page.evaluateJavascript(
                    "document.getElementById('visibleInput').files.length > 0 ? " +
                    "document.getElementById('visibleInput').files[0].name : 'none'"
                )
                val fileSize = page.evaluateJavascript(
                    "document.getElementById('visibleInput').files.length > 0 ? " +
                    "document.getElementById('visibleInput').files[0].size : 0"
                )
                println("[D] files.length=$fileCount, name=$fileName, size=$fileSize")
                if (fileCount.trim().contains("1") && fileName.contains("upload_test_a")) {
                    println("[D] PASSED")
                    passed++
                } else {
                    println("[D] FAILED - files not properly set")
                    failed++
                }
            } catch (e: Exception) {
                println("[D] FAILED with exception: ${e.message}")
                e.printStackTrace()
                failed++
            }

            // ========== Summary ==========
            println("\n====== Test Summary ======")
            println("Passed: $passed")
            println("Failed: $failed")
            println("Total:  ${passed + failed}")

            // Print event log from page
            val eventLog = page.evaluateJavascript("document.getElementById('log').textContent")
            println("\n[Page Event Log]\n$eventLog")

            // Cleanup
            KBrowser.shutdown()

        } catch (e: Exception) {
            println("[FATAL] Unexpected error: ${e.message}")
            e.printStackTrace()
            failed++
        }

        if (failed > 0) {
            println("\n====== SOME TESTS FAILED ======")
            exitProcess(1)
        } else {
            println("\n====== ALL TESTS PASSED ======")
            exitProcess(0)
        }
    }
}
