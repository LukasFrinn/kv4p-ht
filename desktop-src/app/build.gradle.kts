import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.swing)
}

// Reuse the Android app's font (Jost, SIL OFL) straight from upstream.
val upstreamRes = rootDir.resolve("../android-src/KV4PHT/app/src/main/res")
tasks.processResources {
    from(upstreamRes.resolve("font")) {
        include("jost_variable.ttf")
        into("font")
    }
    // Window/taskbar icon (generated from the project's artwork/app-icon.png).
    from("icons") { include("kv4p.png") }
}

val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"

compose.desktop {
    application {
        mainClass = "com.kv4p.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "kv4p-ht-desktop"
            packageVersion = appVersion
            description = "Desktop app for the kv4p HT radio"
            vendor = "kv4p HT community"
            licenseFile.set(rootDir.resolve("../LICENSE"))
            // Simple and safe for a first version; can be trimmed later with suggestRuntimeModules.
            includeAllModules = true

            linux {
                iconFile.set(project.file("icons/kv4p.png"))
                packageName = "kv4p-ht-desktop"
                debMaintainer = "kv4p-ht-desktop@users.noreply.github.com"
                appCategory = "HamRadio"
                menuGroup = "HamRadio"
            }
            macOS {
                iconFile.set(project.file("icons/kv4p.icns"))
                bundleID = "com.kv4p.desktop"
                appCategory = "public.app-category.utilities"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>The microphone is used to transmit your voice through the radio.</string>
                    """.trimIndent()
                }
            }
        }
    }
}
