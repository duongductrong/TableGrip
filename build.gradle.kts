plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.composeDesktop) apply false
}

// `-Ptablegrip.buildDir=build-dev` builds into a separate directory, so tests and compiles don't replace the jars of an
// app started with `:app:run` (which would make it fail to load classes).
providers.gradleProperty("tablegrip.buildDir").orNull?.let { dir ->
    allprojects { layout.buildDirectory = rootProject.layout.projectDirectory.dir("$dir/${project.name}") }
}
