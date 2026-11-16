pluginManagement {
    includeBuild("build-logic")
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
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\..*") } }
    }
}

rootProject.name = "Ondara"

include(":app")
include(
    ":core:model", ":core:domain", ":core:data", ":core:database", ":core:datastore",
    ":core:network", ":core:designsystem", ":core:ui",
)
include(":source:innertube", ":source:resolver", ":source:lyrics", ":source:spotify")
include(":playback")
include(
    ":feature:home", ":feature:search", ":feature:library", ":feature:player",
    ":feature:album", ":feature:artist", ":feature:playlist", ":feature:lyrics",
    ":feature:settings", ":feature:themes", ":feature:news", ":feature:radio", ":feature:account",
)
include(":benchmark")
