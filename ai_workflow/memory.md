# Project Memory

## Project Context
- **Tech Stack**: Kotlin Multiplatform (KMP), Compose Multiplatform (targeting JVM/Desktop, Android, iOS).
- **Core Library**: JCEF (Java Chromium Embedded Framework) for desktop browser embedding.
- **Constraints**: Native desktop CEF binaries are required for runtime; JCEF is JVM-only and depends heavily on Swing/AWT.

## Code Conventions
- **Source Set Separation**: `commonMain` code must remain pure Kotlin and target-agnostic. No JDK platform classes or AWT/Swing UI libraries should be imported in `commonMain`.
- **Platform Implementations**: Any platform-specific UI rendering or native frameworks (like JCEF, WKWebView, Android WebView) must be wrapped behind common interfaces in `commonMain` and implemented in their respective source sets (`jvmMain`, `iosMain`, `androidMain`).

## Key Decisions
- **2026-05-18: JCEF Architecture Alignment Proposal**: Documented that JCEF currently resides incorrectly in `commonMain` and needs to be relocated to `jvmMain`/`desktopMain` with an abstraction interface in `commonMain` to support multi-platform compiles (iOS/Android).

## Gotchas
- **JCEF Class Loading & Imports**: JCEF utilizes heavy AWT/Swing components. Importing `java.awt.*`, `javax.swing.*`, `org.cef.*`, and JDK-specific classes in `commonMain` causes build compilation failures for non-JVM targets (Android, iOS).
- **CDP Debugging Port**: JCEF is configured with a forced debugging port (`58964`) and Alloy mode (Chrome runtime disabled) to ensure seamless embedded automation interface integration.

## Task Log
- **2026-05-18**: Analyzed JCEF code structure, reviewed API coverage, investigated Gradle Multiplatform configurations, and evaluated architectural placement.
