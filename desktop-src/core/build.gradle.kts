plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

// ---------------------------------------------------------------------------
// Upstream sources shared with the Android app.
//
// These files are compiled exactly as they exist in ../android-src, so a
// `git merge upstream/main` brings protocol/modem fixes into the desktop app
// with no porting. Only add files here that have no Android dependencies
// (android.util.Log, androidx.annotation and the usb-serial
// SerialInputOutputManager are stubbed in src/main/java).
// ---------------------------------------------------------------------------
val upstreamJavaDir = rootDir.resolve("../android-src/KV4PHT/app/src/main/java")

// This branch tracks upstream *releases* (currently v2.0.0.1), because that is
// the firmware people actually have on their radios. Upstream main may speak a
// newer, unreleased protocol under the same firmware version number.
val sharedUpstreamSources = listOf(
    "com/vagell/kv4pht/radio/Protocol.java",
    "com/vagell/kv4pht/radio/RadioModuleController.java",
    "com/vagell/kv4pht/radio/OpusUtils.java",
    "com/vagell/kv4pht/radio/MicGainBoost.java",
    "com/vagell/kv4pht/radio/RadioMode.java",
    // APRS: the firmware demodulates/modulates AFSK itself and exchanges AX.25
    // frames over KISS, so only the packet classes and the APRS parser are needed.
    "com/vagell/kv4pht/javAX25/ax25/Packet.java",
    "com/vagell/kv4pht/javAX25/ax25/Arrays.java",
)

// Whole upstream directories shared as-is.
val sharedUpstreamDirs = listOf(
    "com/vagell/kv4pht/aprs/parser",
)

val syncUpstreamSources by tasks.registering(Sync::class) {
    description = "Copies the shared Java sources from android-src into the build."
    from(upstreamJavaDir) {
        include(sharedUpstreamSources)
        sharedUpstreamDirs.forEach { include("$it/*.java") }
    }
    into(layout.buildDirectory.dir("generated/upstream-java"))
    doFirst {
        val missing = sharedUpstreamSources.filterNot { upstreamJavaDir.resolve(it).isFile } +
            sharedUpstreamDirs.filterNot { upstreamJavaDir.resolve(it).isDirectory }
        check(missing.isEmpty()) {
            "Shared upstream sources moved or were deleted: $missing. Update core/build.gradle.kts."
        }
    }
}

sourceSets {
    main {
        java.srcDir(syncUpstreamSources)
    }
}

dependencies {
    api(libs.jserialcomm)
    api(libs.concentus)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(libs.junit)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
