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
    }
}

rootProject.name = "ApexTuner"

include(":app")
include(":core")
include(":feature:dashboard")
include(":feature:cleaner")
include(":feature:battery")
include(":feature:memory")
include(":feature:appmanager")
include(":feature:network")
include(":feature:notifications")
include(":feature:files")
include(":feature:contacts")
include(":feature:tools")
include(":feature:settings")
include(":feature:billing")
