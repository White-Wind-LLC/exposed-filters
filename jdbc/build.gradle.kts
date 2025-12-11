plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(kotlin("reflect"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.h2)
    testImplementation(libs.exposed.kotlin.datetime)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.kotlinx.serialization)
    testImplementation(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    compilerOptions {
        // Enable experimental context parameters feature used in this module
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(project.group.toString(), "exposed-filters-jdbc", project.version.toString())

    pom {
        name.set(project.properties["pomName"] as String)
        description.set(project.properties["pomDescription"] as String)
        inceptionYear.set(project.properties["year"] as String)
        url.set(project.properties["repositoryUrl"] as String)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(project.properties["developerId"] as String)
                name.set(project.properties["developerName"] as String)
                url.set(project.properties["developerUrl"] as String)
            }
        }
        scm {
            url.set(project.properties["repositoryUrl"] as String)
            connection.set(project.properties["repositoryConnection"] as String)
            developerConnection.set(project.properties["repositoryDeveloperConnection"] as String)
            tag.set("HEAD")
        }
    }
}
