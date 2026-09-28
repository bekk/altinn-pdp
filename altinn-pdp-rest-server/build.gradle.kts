plugins {
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

application {
    mainClass = "no.kartverket.altinnpdp.restserver.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

val tools: SourceSet by sourceSets.creating

configurations.named(tools.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}

configurations.named(tools.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.runtimeOnly.get())
}

tasks.check {
    dependsOn(tools.classesTaskName)
}

tasks.register<JavaExec>("generateOpenApiSpec") {
    group = "build"
    description = "Writes openapi.json from the spec the running server serves."
    mainClass = "no.kartverket.altinnpdp.restserver.GenerateOpenApiSpecKt"
    classpath = tools.runtimeClasspath
    workingDir = projectDir
}

dependencies {
    implementation(project(":altinn-pdp-client"))

    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.di)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    "toolsImplementation"(sourceSets.main.get().output)
    "toolsImplementation"(libs.kotlinx.coroutines.core)

    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.nimbus.jose.jwt)
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = findProperty("dockerImage")?.toString() ?: "altinn-pdp-rest-server:local"
    }
    container {
        ports = listOf("8080")
        user = "1000:1000"
    }
}
