Web Search Results:
1. In Compose Desktop, `SwingPanel` (JCEF) is a heavyweight component.
2. On Mac, JCEF allocates a native `NSView` which always renders on top of the JVM window.
3. Lightweight components (Compose UI, `JPanel`, `MaskGlassPane`) are physically incapable of rendering over a heavyweight `NSView`.
4. The ONLY workarounds for JCEF overlays in non-OSR mode are:
   - Separate floating OS windows.
   - Injecting HTML/CSS overlays directly into the DOM.
