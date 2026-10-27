buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // The Android Gradle plugin sits on the root classpath next to the Kotlin plugin, so the
        // Kotlin Android plugin can see it. Skipped with -Psde.android=false (engine-only builds).
        if (providers.gradleProperty("sde.android").orNull != "false") {
            classpath("com.android.tools.build:gradle:8.13.0")
        }
    }
}

plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
