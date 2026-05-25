import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.lzdev42"
val releaseVersion: String = project.findProperty("RELEASE_VERSION") as? String ?: "0.1.0"
version = releaseVersion

mavenPublishing {
    coordinates("io.github.lzdev42", "kbrowser", releaseVersion)

    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("KBrowser")
        description.set("A Kotlin Multiplatform browser automation library")
        inceptionYear.set("2026")
        url.set("https://github.com/lzdev42/KBrowser")
        licenses {
            license {
                name.set("The MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("lzdev42")
                name.set("lzdev42")
                url.set("https://github.com/lzdev42/")
            }
        }
        scm {
            url.set("https://github.com/lzdev42/KBrowser")
            connection.set("scm:git:git://github.com/lzdev42/KBrowser.git")
            developerConnection.set("scm:git:ssh://git@github.com/lzdev42/KBrowser.git")
        }
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    androidLibrary {
        namespace = "xyz.kbrowser.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.webkit)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}