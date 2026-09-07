// Common config (Kotlin plugin, group/version, jvmToolchain, kotlin-test) lives in the root build.gradle.kts.

plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nimbus.jose.jwt)

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
