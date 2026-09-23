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

// Conditionally include samples and benchmarks to avoid configuring/running their tasks in CI/publish
val excludeSamples: Boolean = gradle.startParameter.projectProperties["excludeSamples"]?.toBoolean() ?: false
if (!excludeSamples) {
    include("example")
    include("benchmark")
}