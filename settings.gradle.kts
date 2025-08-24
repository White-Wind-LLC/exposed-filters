pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "exposed-filters"

include("core")
include("jdbc")
include("rest")

// Conditionally include samples to avoid configuring/running its tasks in CI/publish
val excludeSamples: Boolean = gradle.startParameter.projectProperties["excludeSamples"]?.toBoolean() ?: false
if (!excludeSamples) {
    include("example")
}