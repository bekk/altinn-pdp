package no.kartverket.altinnpdp.restserver.authorize

import io.ktor.http.HttpHeaders
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import no.kartverket.altinnpdp.client.PdpClient
import no.kartverket.altinnpdp.restserver.authorize.cache.PdpDecisionCache

@OptIn(ExperimentalKtorApi::class)
internal fun Route.authorize(pdpClient: PdpClient, decisionCache: PdpDecisionCache) {
    post("/authorize") {
        val request = call.receive<AuthorizeRequest>().parse()
        val maxAge = call.request.headers.maxAge()
        val (authorization, age) = decisionCache.get(request, maxAge) {
            pdpClient.authorize(
                subject = request.subject,
                resourceId = request.resourceId,
                customerOrganizationNumber = request.customerOrganizationNumber,
                action = request.action,
                resourceAttributes = request.resourceAttributes,
            )
        }

        call.response.header(HttpHeaders.Age, age.seconds)
        call.respond(
            AuthorizeResponse(
                permit = authorization.isPermit,
                decision = authorization.decision,
                status = authorization.status?.value,
                minimumAuthenticationLevel = authorization.minimumAuthenticationLevel,
            ),
        )
    }.describe(authorizeOperation)
}
