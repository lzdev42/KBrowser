package xyz.kbrowser.webview

class ElementNotFoundException(val refid: String) :
    Exception("refid '$refid' not found — the node cache is empty or stale. Call snapshot() first and use refids from its result.")
