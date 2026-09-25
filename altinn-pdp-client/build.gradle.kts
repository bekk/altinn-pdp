import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Common config (Kotlin plugin, group/version, jvmToolchain, kotlin-test, JUnit Platform) lives in the root build.gradle.kts.

plugins {
    alias(libs.plugins.kotlin.serialization)
}

extensions.configure<KotlinJvmProjectExtension> {
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nimbus.jose.jwt)
}
