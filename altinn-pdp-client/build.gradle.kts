plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
    explicitApi()
}

ktlint {
    version.set(libs.versions.ktlint.cli.get())
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.nocommons)
    testFixturesImplementation(libs.nimbus.jose.jwt)

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
