package xyz.kbrowser.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Plugin that registers a GenerateFontPathsTask for a Kotlin Multiplatform project.
 *
 * Usage in consumer build.gradle.kts:
 * ```
 * plugins {
 *     id("xyz.kbrowser.font-paths")
 * }
 *
 * kbrowserFontPaths {
 *     packageName.set("com.example.app")
 * }
 * ```
 *
 * Scans `src/commonMain/composeResources/font/` at build time and generates
 * `FontPaths.generated.kt` in the wasmJsMain source set.
 */
class FontPathsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kbrowserFontPaths", FontPathsExtension::class.java)

        val taskName = "generateFontPaths"
        val taskProvider = project.tasks.register<GenerateFontPathsTask>(taskName) {
            group = "kbrowser"
            description = "Scans composeResources/font/ and generates FontPaths.generated.kt"
            packageName.set(extension.packageName)
            fontDirPath.set(project.layout.projectDirectory.dir("src/commonMain/composeResources/font").asFile.absolutePath)
            outputDir.set(project.layout.buildDirectory.dir("generated/kbrowser/wasmJsMain/kotlin"))
        }

        // Hook into Kotlin Multiplatform source sets via dynamic access
        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate {
                val kotlinExt = project.extensions.findByName("kotlin") ?: return@afterEvaluate
                val sourceSets = kotlinExt.javaClass.getMethod("getSourceSets").invoke(kotlinExt)
                val sourceSet = sourceSets.javaClass.getMethod("getByName", String::class.java).invoke(sourceSets, "wasmJsMain")
                val kotlin = sourceSet.javaClass.getMethod("getKotlin").invoke(sourceSet)
                // Use Object varargs version of srcDir
                val srcDirDir = kotlin.javaClass.getMethod("srcDir", Any::class.java)
                val genDir = project.layout.buildDirectory.dir("generated/kbrowser/wasmJsMain/kotlin").get().asFile
                srcDirDir.invoke(kotlin, genDir)
            }
        }

        // Make sure generated file exists before Kotlin compilation
        project.tasks.matching { it.name.startsWith("compileKotlinWasmJs") }.configureEach {
            dependsOn(taskName)
        }
    }
}

interface FontPathsExtension {
    val packageName: org.gradle.api.provider.Property<String>
}
