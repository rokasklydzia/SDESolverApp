pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SDESolver"
include(":shared")

// Pass -Psde.android=false to build and test only the maths engine, without the Android SDK.
if (providers.gradleProperty("sde.android").orNull != "false") include(":androidApp")
