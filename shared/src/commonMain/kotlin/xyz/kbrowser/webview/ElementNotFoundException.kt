package xyz.kbrowser.webview

class ElementNotFoundException(refid: String) : Exception("Element with refid '$refid' not found in AxTree Map.")
