// Jib shells out to Apache Commons Compress to read Zstandard-compressed layers (DHI base images use zstd), but
// Commons Compress needs the zstd-jni library on jib's own classloader to do so. Adding it as a project dependency
// is not enough: it must be on the buildscript classpath so jib itself can see it.
buildscript {
    dependencies {
        classpath(libs.zstd.jni)
    }
}

plugins {
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

val dockerImage = providers.gradleProperty("dockerImage").orElse("altinn-pdp-rest-server:local")
val dockerTags = providers.gradleProperty("dockerTags").orNull
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.toSet()
    ?: emptySet()

application {
    mainClass = "no.kartverket.altinnpdp.restserver.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    inputs.file("openapi.json")
}

tasks.register<Test>("generateOpenApiSpec") {
    group = "build"
    description = "Writes openapi.json from the spec the running server serves."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*.OpenApiSpecFileTest") }
    systemProperty("openApiSpec.write", "true")
    outputs.upToDateWhen { false }
}

dependencies {
    implementation(project(":altinn-pdp-client"))

    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.di)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.nocommons)
    implementation(libs.caffeine)

    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.nimbus.jose.jwt)
}

jib {
    from {
        image = "dhi.io/eclipse-temurin:25-alpine3.24@sha256:0f947299651eea29537b025c58884ed363265277c9100dcc1b91ccdf9f2d1db3"
    }
    to {
        image = dockerImage.get()
        tags = dockerTags
    }
    container {
        ports = listOf("8080")
        user = "1000:1000"
    }
}
