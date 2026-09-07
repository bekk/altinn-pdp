plugins {
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

application {
    mainClass = "no.bekk.altinnpdp.restserver.MainKt"
}

dependencies {
    implementation(project(":altinn-pdp-client"))

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

// Local-first Jib config: `./gradlew jibDockerBuild` needs no registry.
// Override the target image in CI with `-PdockerImage=<registry>/altinn-pdp-rest-server:<tag>`.
jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = findProperty("dockerImage")?.toString() ?: "altinn-pdp-rest-server:local"
    }
    container {
        mainClass = "no.bekk.altinnpdp.restserver.MainKt"
        ports = listOf("8080")
    }
}
