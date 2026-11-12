// Jib shells out to Apache Commons Compress to read Zstandard-compressed layers (DHI base images use zstd), but
// Commons Compress needs the zstd-jni library on jib's own classloader to do so. Adding it as a project dependency
// is not enough: it must be on the buildscript classpath so jib itself can see it.
buildscript {
    dependencies {
        classpath(libs.zstd.jni)
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
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

kotlin {
    jvmToolchain(25)
}

ktlint {
    version.set(libs.versions.ktlint.cli.get())
}

application {
    mainClass = "no.kartverket.altinnpdp.restserver.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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
    // ktor pulls in an older netty with known vulnerabilities
    implementation(platform(libs.netty.bom))
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.di)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    // logstash-logback-encoder pulls in an older Jackson 3 with known vulnerabilities.
    implementation(platform(libs.jackson.bom))
    implementation(libs.nocommons)
    implementation(libs.caffeine)
    implementation(libs.jedis)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.params)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(testFixtures(project(":altinn-pdp-client")))
    testImplementation(libs.jedis.mock)
}

jib {
    from {
        image = "dhi.io/eclipse-temurin:25-alpine3.24@sha256:8da329a70f7794dd59467841e7476fd96a22fa5d3146c36811d3589b5205a5d0"
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

tasks.matching { it.name.startsWith("jib") }.configureEach {
    notCompatibleWithConfigurationCache("Jib's tasks hold on to the Gradle project")
}
