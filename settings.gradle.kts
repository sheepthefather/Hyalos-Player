pluginManagement {
    repositories {
        // `google()` must come first: the Android Gradle Plugin and everything
        // under androidx live there, and resolving them from Maven Central
        // either fails or picks up a stale mirror.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HyalosPlayer"
include(":app")
