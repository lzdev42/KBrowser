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

tasks.register<JavaExec>("runDebug") {
    group = "application"
    mainClass.set("xyz.kbrowser.webview.InteractiveDebugRunnerKt")
    val compileTestKotlin = tasks.named("compileTestKotlin")
    classpath = files(compileTestKotlin, configurations.named("testRuntimeClasspath"))
    jvmArgs(
        "--enable-native-access=jcef",
        "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
        "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
    )
}