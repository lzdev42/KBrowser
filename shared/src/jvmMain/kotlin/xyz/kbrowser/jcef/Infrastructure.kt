package xyz.kbrowser.jcef

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ported from com.intellij.openapi.Disposable
 */
interface Disposable {
    fun dispose()
}

/**
 * Ported from com.intellij.openapi.util.Disposer
 * Handles parent-child disposal relationships.
 */
object Disposer {
    private val childrenMap = mutableMapOf<Disposable, MutableList<Disposable>>()

    @Synchronized
    fun register(parent: Disposable, child: Disposable) {
        childrenMap.computeIfAbsent(parent) { CopyOnWriteArrayList() }.add(child)
    }

    @Synchronized
    fun dispose(disposable: Disposable) {
        val children = childrenMap.remove(disposable)
        children?.forEach { dispose(it) }
        disposable.dispose()
    }
}
