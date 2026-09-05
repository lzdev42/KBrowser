import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
    implementation(libs.kotlinx.serialization.json)
}

compose.desktop {
    application {
        mainClass = "xyz.kbrowser.MainKt"
        jvmArgs += listOf(
            "--enable-native-access=jcef",
            "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
            "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "xyz.kbrowser"
            packageVersion = "1.0.0"
        }
    }
}

// Integration tests: each is a `fun main()` that drives a real JCEF browser and
// exits non-zero on failure, so they double as JavaExec tasks.
val testJvmArgs = listOf(
    "--enable-native-access=jcef",
    "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
    "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
)

fun registerKBrowserTest(name: String, mainClass: String) {
    tasks.register<JavaExec>(name) {
        group = "application"
        this.mainClass.set(mainClass)
        val compileTestKotlin = tasks.named("compileTestKotlin")
        classpath = files(compileTestKotlin, configurations.named("testRuntimeClasspath"))
        jvmArgs(testJvmArgs)
        workingDir = rootProject.projectDir
    }
}

registerKBrowserTest("runFileUploadTest", "xyz.kbrowser.FileUploadTestKt")
registerKBrowserTest("runTimingLoadTest", "xyz.kbrowser.TimingLoadTestKt")
registerKBrowserTest("runNonOsrLoadHtmlTest", "xyz.kbrowser.NonOsrLoadHtmlTestKt")
registerKBrowserTest("runResizeFollowTest", "xyz.kbrowser.ResizeFollowTestKt")
registerKBrowserTest("runNonOsrResizeTest", "xyz.kbrowser.NonOsrResizeTestKt")
registerKBrowserTest("runKBDebugTest", "xyz.kbrowser.KBDebugTestKt")
