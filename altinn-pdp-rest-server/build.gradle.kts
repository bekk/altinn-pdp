plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.jib)
}

group = "no.bekk.altinnpdp"
version = "0.1.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.openapi)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
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
        mainClass = "io.ktor.server.netty.EngineMain"
        ports = listOf("8080")
    }
}
