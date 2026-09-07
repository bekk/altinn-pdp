plugins {
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.jib)
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

dependencies {
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.openapi)
    implementation(ktorLibs.server.routingOpenapi)
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
        mainClass = "io.ktor.server.netty.EngineMain"
        ports = listOf("8080")
    }
}
