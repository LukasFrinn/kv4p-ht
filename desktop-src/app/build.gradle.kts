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

// macOS: small Swift helper that asks Location Services for the Mac's position (APRS beacon).
// Compose ships <appResources>/macos-arm64 inside the .app (compose.application.resources.dir).
val isMacHost = System.getProperty("os.name").lowercase().contains("mac")
val macArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"
val appResources = layout.buildDirectory.dir("appResources")
val buildLocateHelper by tasks.registering(Exec::class) {
    val source = file("macos/locate/main.swift")
    val plist = file("macos/locate/Info.plist")
    val bundle = appResources.map { it.dir("macos-$macArch/kv4p-locate.app") }
    onlyIf { isMacHost }
    inputs.files(source, plist)
    outputs.dir(bundle)
    doFirst {
        val contents = bundle.get().asFile.resolve("Contents")
        contents.resolve("MacOS").mkdirs()
        plist.copyTo(contents.resolve("Info.plist"), overwrite = true)
    }
    commandLine(
        "swiftc", "-O", source.absolutePath,
        "-o", bundle.get().asFile.resolve("Contents/MacOS/kv4p-locate").absolutePath,
    )
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
            appResourcesRootDir.set(appResources)
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
                        <key>NSLocationUsageDescription</key>
                        <string>Your location is filled into the APRS position beacon when you ask for it.</string>
                        <key>NSLocationWhenInUseUsageDescription</key>
                        <string>Your location is filled into the APRS position beacon when you ask for it.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildLocateHelper) }
// Packaging drops the helper's executable bit; put it back in the finished .app.
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        fileTree(layout.buildDirectory.dir("compose/binaries")) { include("**/kv4p-locate") }
            .forEach { it.setExecutable(true, false) }
        // Sign the helper as a bundle so Location Services can identify it (ad-hoc is enough).
        if (isMacHost) {
            fileTree(layout.buildDirectory.dir("compose/binaries")).matching { include("**/kv4p-locate") }
                .forEach { exe ->
                    val app = exe.parentFile.parentFile.parentFile
                    providers.exec { commandLine("codesign", "--force", "--sign", "-", app.absolutePath) }.result.get()
                }
        }
    }
}
