package xyz.kbrowser.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AxTreeSnapshotTest {

    @Test
    fun testToYamlSnapshotClean() {
        // 构造一个模拟的 AxTreeData
        // 视口大小：innerWidth=1000, innerHeight=800, scrollX=0, scrollY=0
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
            childIds = listOf("r3", "r5") // r3 在视口内，r5 在视口外
        )
        // 3 是个空的 generic div，里面包含 4 (button)
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
        // 5 是一个在视口外的按钮 (centerY = 1000, 视口 bottom = 800)
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

        // 验证：
        // 1. 包含 url
        assertTrue(cleanYaml.contains("url: \"https://example.com\""))
        // 2. 按钮 r4 应该被包含，且文本被合并为 "确认"
        assertTrue(cleanYaml.contains("refid: \"r4\""))
        assertTrue(cleanYaml.contains("text: \"确认\""))
        // 3. 按钮 r5 在视口外，应该被过滤掉
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
        // r3 是一个空的 generic div，里面包含 r4 (button) 和 r6 (button)
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

        // 验证：
        // 1. r3 作为一个分组容器必须被保留，因为它含有两个有效子节点
        assertTrue(cleanYaml.contains("refid: \"r3\""), "r3 容器节点应该保留")
        // 2. r4 和 r6 在 r3 之下被缩进输出
        assertTrue(cleanYaml.contains("refid: \"r4\""), "r4 应该包含")
        assertTrue(cleanYaml.contains("refid: \"r6\""), "r6 应该包含")
    }
}
