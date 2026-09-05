package xyz.kbrowser.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AxTreeSnapshotTest {

    @Test
    fun testToYamlSnapshotClean() {
        val rootNode = AxNode(
            refid = "r1",
            tagName = "#document",
            role = "RootWebArea",
            centerX = 500,
            centerY = 400,
            width = 1000,
            height = 800,
            isVisible = true,
            nodeId = "1",
            childIds = listOf("r2")
        )
        val bodyNode = AxNode(
            refid = "r2",
            tagName = "body",
            role = "generic",
            centerX = 500,
            centerY = 400,
            width = 1000,
            height = 800,
            isVisible = true,
            nodeId = "2",
            childIds = listOf("r3", "r5") // r3 is in the viewport, r5 is outside
        )
        // r3 is an empty generic div containing r4 (button)
        val divNode = AxNode(
            refid = "r3",
            tagName = "div",
            role = "generic",
            centerX = 200,
            centerY = 200,
            width = 100,
            height = 50,
            isVisible = true,
            nodeId = "3",
            childIds = listOf("r4")
        )
        val buttonNode = AxNode(
            refid = "r4",
            tagName = "button",
            role = "button",
            centerX = 200,
            centerY = 200,
            width = 100,
            height = 50,
            isVisible = true,
            nodeId = "4",
            childIds = listOf("r4-text")
        )
        val textNode = AxNode(
            refid = "r4-text",
            tagName = "#text",
            role = "StaticText",
            text = "确认",
            centerX = 200,
            centerY = 200,
            width = 80,
            height = 30,
            isVisible = true,
            nodeId = "4-text"
        )
        // r5 is a button outside the viewport (centerY = 1000, viewport bottom = 800)
        val outOfViewportButton = AxNode(
            refid = "r5",
            tagName = "button",
            role = "button",
            centerX = 200,
            centerY = 1000,
            width = 100,
            height = 50,
            isVisible = true,
            nodeId = "5",
            childIds = listOf("r5-text")
        )
        val outOfViewportText = AxNode(
            refid = "r5-text",
            tagName = "#text",
            role = "StaticText",
            text = "视口外按钮",
            centerX = 200,
            centerY = 1000,
            width = 80,
            height = 30,
            isVisible = true,
            nodeId = "5-text"
        )

        val axTreeData = AxTreeData(
            url = "https://example.com",
            innerWidth = 1000,
            innerHeight = 800,
            scrollX = 0,
            scrollY = 0,
            nodes = listOf(rootNode, bodyNode, divNode, buttonNode, textNode, outOfViewportButton, outOfViewportText)
        )

        println("=== 运行 toYamlSnapshot(SnapshotMode.VIEWPORT) ===")
        val cleanYaml = axTreeData.toYamlSnapshot(SnapshotMode.VIEWPORT)
        println(cleanYaml)

        assertTrue(cleanYaml.contains("url: \"https://example.com\""))
        assertTrue(cleanYaml.contains("refid: \"r4\""))
        assertTrue(cleanYaml.contains("text: \"确认\""))
        assertTrue(!cleanYaml.contains("refid: \"r5\""))
        assertTrue(!cleanYaml.contains("视口外按钮"))
    }

    @Test
    fun testGroupProtection() {
        val rootNode = AxNode(
            refid = "r1",
            tagName = "#document",
            role = "RootWebArea",
            centerX = 500,
            centerY = 400,
            width = 1000,
            height = 800,
            isVisible = true,
            nodeId = "1",
            childIds = listOf("r2")
        )
        val bodyNode = AxNode(
            refid = "r2",
            tagName = "body",
            role = "generic",
            centerX = 500,
            centerY = 400,
            width = 1000,
            height = 800,
            isVisible = true,
            nodeId = "2",
            childIds = listOf("r3")
        )
        // r3 is an empty generic div containing r4 (button) and r6 (button)
        val divNode = AxNode(
            refid = "r3",
            tagName = "div",
            role = "generic",
            centerX = 200,
            centerY = 200,
            width = 200,
            height = 50,
            isVisible = true,
            nodeId = "3",
            childIds = listOf("r4", "r6")
        )
        val buttonNode1 = AxNode(
            refid = "r4",
            tagName = "button",
            role = "button",
            centerX = 150,
            centerY = 200,
            width = 80,
            height = 50,
            isVisible = true,
            nodeId = "4",
            childIds = listOf("r4-text")
        )
        val textNode1 = AxNode(
            refid = "r4-text",
            tagName = "#text",
            role = "StaticText",
            text = "确认",
            centerX = 150,
            centerY = 200,
            width = 60,
            height = 30,
            isVisible = true,
            nodeId = "4-text"
        )
        val buttonNode2 = AxNode(
            refid = "r6",
            tagName = "button",
            role = "button",
            centerX = 250,
            centerY = 200,
            width = 80,
            height = 50,
            isVisible = true,
            nodeId = "6",
            childIds = listOf("r6-text")
        )
        val textNode2 = AxNode(
            refid = "r6-text",
            tagName = "#text",
            role = "StaticText",
            text = "取消",
            centerX = 250,
            centerY = 200,
            width = 60,
            height = 30,
            isVisible = true,
            nodeId = "6-text"
        )

        val axTreeData = AxTreeData(
            url = "https://example.com",
            innerWidth = 1000,
            innerHeight = 800,
            scrollX = 0,
            scrollY = 0,
            nodes = listOf(rootNode, bodyNode, divNode, buttonNode1, textNode1, buttonNode2, textNode2)
        )

        println("=== 运行 toYamlSnapshot(SnapshotMode.VIEWPORT) 验证分组保护 ===")
        val cleanYaml = axTreeData.toYamlSnapshot(SnapshotMode.VIEWPORT)
        println(cleanYaml)

        // r3 must be kept as a group container because it holds two valid children
        assertTrue(cleanYaml.contains("refid: \"r3\""), "r3 容器节点应该保留")
        assertTrue(cleanYaml.contains("refid: \"r4\""), "r4 应该包含")
        assertTrue(cleanYaml.contains("refid: \"r6\""), "r6 应该包含")
    }
}
