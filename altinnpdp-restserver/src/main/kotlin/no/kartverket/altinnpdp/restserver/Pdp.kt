package no.bekk.altinnpdp.restserver

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import no.bekk.altinnpdp.AltinnEnvironment
import no.bekk.altinnpdp.auth.AltinnScopes
import no.bekk.altinnpdp.auth.MaskinportenAltinnTokenProvider
import no.bekk.altinnpdp.auth.MaskinportenConfig
import no.bekk.altinnpdp.client.PdpClient

/**
 * Registers the [PdpClient] every `/authorize` call resolves via Ktor's DI plugin
 * (`val pdpClient: PdpClient by dependencies` in a route). Defaults to one built from
 * environment variables / `.env` (see `.env.example`) - pass [client] explicitly in tests instead
 * of setting up real Maskinporten credentials.
 *
 * Ktor's engine installs [DI] itself when running from `application.yaml` (so config-based
 * dependency registration works even for modules that never mention DI) - `install(DI)` here
 * would then throw [io.ktor.server.application.DuplicatePluginException]. `testApplication`, on
 * the other hand, never installs it up front, so this module still has to when running under
 * test. [pluginOrNull] covers both.
 */
fun Application.configurePdp(client: PdpClient = pdpClientFromEnv()) {
    if (pluginOrNull(DI) == null) install(DI)
    dependencies.provide<PdpClient> { client }
}

private fun pdpClientFromEnv(): PdpClient {
    val environment = AltinnEnvironment.valueOf(Dotenv.get("ALTINN_ENVIRONMENT") ?: "TT02")
    val subscriptionKey = requiredEnv("ALTINN_SUBSCRIPTION_KEY")
    val maskinportenConfig = MaskinportenConfig(
        tokenUrl = Dotenv.get("MASKINPORTEN_TOKEN_URL") ?: "https://test.maskinporten.no/token",
        clientId = requiredEnv("MASKINPORTEN_CLIENT_ID"),
        jwk = requiredEnv("MASKINPORTEN_CLIENT_JWK"),
        scopes = (AltinnScopes.AUTHORIZE).split(" "),
    )
    val tokenProvider = MaskinportenAltinnTokenProvider(maskinportenConfig, environment)
    return PdpClient(environment, tokenProvider, subscriptionKey)
}

private fun requiredEnv(name: String): String =
    Dotenv.get(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable $name (see .env.example)")
