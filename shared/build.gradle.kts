import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    // The Android app consumes the JVM build. iOS targets are added with the iPhone port (step 4);
    // until then commonMain must stay free of JVM-only APIs so that port is a build change, not a rewrite.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

tasks.withType<Test>().configureEach {
    // Show the benchmark and convergence numbers the tests print.
    testLogging {
        showStandardStreams = providers.gradleProperty("sde.showOutput").orNull == "true"
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
