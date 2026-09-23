// JMH benchmarks: library overhead vs the equivalent hand-written Exposed DSL. Not published.
// Run: ./gradlew :benchmark:jmh [-PjmhInclude=<regex>]  (see benchmark/README.md)
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("me.champeau.jmh") version "0.7.3"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    jmhImplementation(project(":core"))
    jmhImplementation(project(":jdbc"))
    jmhImplementation(project(":rest"))
    jmhImplementation(libs.exposed.core)
    jmhImplementation(libs.exposed.jdbc)
    jmhImplementation(libs.h2)
}

jmh {
    jmhVersion.set("1.37")
    fork.set(2)
    warmupIterations.set(5)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("us")
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    (project.findProperty("jmhInclude") as String?)?.let { includes.set(listOf(it)) }
}
