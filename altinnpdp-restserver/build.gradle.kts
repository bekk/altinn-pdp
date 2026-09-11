plugins {
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

application {
    mainClass = "no.kartverket.altinnpdp.restserver.MainKt"
}

dependencies {
    implementation(project(":altinnpdp-client"))

    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.openapi)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.di)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(ktorLibs.server.testHost)
}

tasks.named<JavaExec>("run") {
  workingDir = rootProject.projectDir
}

// Local-first Jib config: `./gradlew jibDockerBuild` needs no registry.
// Override the target image in CI with `-PdockerImage=<registry>/altinnpdp-restserver:<tag>`.
jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = findProperty("dockerImage")?.toString() ?: "altinnpdp-restserver:local"
    }
    container {
        mainClass = "no.kartverket.altinnpdp.restserver.MainKt"
        ports = listOf("8080")
    }
}
