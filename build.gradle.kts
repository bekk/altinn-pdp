import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

val ktlintVersion = libs.versions.ktlint.cli.get()

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    group = "no.kartverket"
    version = "0.1.0-SNAPSHOT"

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    // Reglene som faktisk håndheves står i .editorconfig.
    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
    }
}
