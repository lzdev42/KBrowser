// 调试脚本：查看薪资待遇下拉菜单的实际层级关系

fun debugAxTree(nodes: List<AxNode>) {
    println("=== 调试：薪资待遇相关节点 ===\n")
    
    // 找到所有薪资相关的节点
    val salaryNodes = nodes.filter { 
        it.text.contains("薪资") || 
        it.text.contains("K") || 
        it.text.contains("不限") ||
        it.className.contains("condition-filter-select") ||
        it.className.contains("filter-select-dropdown")
    }
    
    println("找到 ${salaryNodes.size} 个相关节点：\n")
    
    for (node in salaryNodes) {
        println("refid: ${node.refid}")
        println("  role: ${node.role}")
        println("  text: ${node.text}")
        println("  tagName: ${node.tagName}")
        println("  className: ${node.className}")
        println("  isVisible: ${node.isVisible}")
        println("  center: (${node.centerX}, ${node.centerY})")
        println("  nodeId: ${node.nodeId}")
        println("  childIds: ${node.childIds}")
        println()
    }
    
    // 查看 childIds 映射关系
    println("\n=== childIds 映射关系 ===\n")
    val nodeMap = nodes.associateBy { it.refid }
    
    for (node in salaryNodes.take(5)) {
        if (node.childIds.isNotEmpty()) {
            println("${node.refid} (${node.text.take(20)}) 的子节点：")
            for (childRefid in node.childIds) {
                val child = nodeMap[childRefid]
                if (child != null) {
                    println("  └─ $childRefid: ${child.role} \"${child.text.take(30)}\" visible=${child.isVisible}")
                } else {
                    println("  └─ $childRefid: [未找到映射]")
                }
            }
            println()
        }
    }
}
