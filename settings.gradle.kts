// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    // Fail loudly if a subproject declares its own repository, so dependency
    // resolution stays reproducible.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "sports-hub-android"
include(":app")
