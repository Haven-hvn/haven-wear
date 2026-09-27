pluginManagement {
    repositories {
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
        // Temporary: foc-cache 0.2.0 is not on Maven Central yet. Publish it from
        // foc-local-first-android (`./gradlew :foc-cache:publishToMavenLocal`) before
        // building. Remove this line once 0.2.0 is published.
        mavenLocal()
    }
}

rootProject.name = "haven-wear"

include(":app")
