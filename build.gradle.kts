plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = project.properties["group"] as String
    version = project.properties["version"] as String

    repositories {
        mavenCentral()
    }

    // Apply Dokka to root and all subprojects so documentation can be generated across modules
    apply(plugin = "org.jetbrains.dokka")
}

// Convenience task to generate documentation for the whole project
tasks.register("generateDocs") {
    // Dokka 2.0.0 provides a unified task `dokkaGenerate` for single- and multi-module builds
    dependsOn(":dokkaGenerate")
}