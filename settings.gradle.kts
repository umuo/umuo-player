pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories { google(); mavenCentral() }
}
rootProject.name = "UmuoPlayer"
include(":shared", ":playback-media3", ":ui-mobile", ":ui-tv", ":app-mobile", ":app-tv")
