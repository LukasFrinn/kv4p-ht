pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "kv4p-ht-desktop"

// core: plain-Java radio logic shared with the Android app (compiled straight
//       from ../android-src) plus the desktop transport/session glue.
// app:  Compose Desktop UI, audio, packaging.
include(":core", ":app")
