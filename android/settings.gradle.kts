// Google's Maven can be swapped for a mirror on networks where dl.google.com is
// slow or blocked. Enable per machine in ~/.gradle/gradle.properties:
//   hypernova.googleMirror=https://maven.aliyun.com/repository/google
// With the mirror set, dl.google.com is never contacted.
pluginManagement {
    val mirror: String? = providers.gradleProperty("hypernova.googleMirror").orNull
    repositories {
        val googleGroups: org.gradle.api.artifacts.repositories.RepositoryContentDescriptor.() -> Unit = {
            includeGroupByRegex("com\\.android.*")
            includeGroupByRegex("com\\.google.*")
            includeGroupByRegex("androidx.*")
        }
        if (mirror != null) maven(mirror) { content(googleGroups) } else google { content(googleGroups) }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val mirror: String? = providers.gradleProperty("hypernova.googleMirror").orNull
    repositories {
        val googleGroups: org.gradle.api.artifacts.repositories.RepositoryContentDescriptor.() -> Unit = {
            includeGroupByRegex("com\\.android.*")
            includeGroupByRegex("com\\.google.*")
            includeGroupByRegex("androidx.*")
        }
        if (mirror != null) maven(mirror) { content(googleGroups) } else google { content(googleGroups) }
        mavenCentral()
    }
}

rootProject.name = "Hypernova"
include(":app")
include(":shared")
