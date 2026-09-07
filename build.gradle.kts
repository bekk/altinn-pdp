import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "no.bekk.altinnpdp"
    version = "0.1.0-SNAPSHOT"

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
    }
}
