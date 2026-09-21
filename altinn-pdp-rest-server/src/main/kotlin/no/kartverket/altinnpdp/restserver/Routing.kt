package no.kartverket.altinnpdp.restserver

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import no.kartverket.altinnpdp.client.PdpClient

fun Application.configureRouting() {
    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK)
        }

        post("/authorize") {
            val pdpClient: PdpClient by dependencies
            val request = call.receive<AuthorizeRequest>().validated()
            val authorization = pdpClient.authorize(
                systemuserId = request.systemuserId,
                resourceId = request.resourceId,
                customerOrganizationNumber = request.customerOrganizationNumber,
                action = request.action,
            )

            call.respond(
                AuthorizeResponse(
                    permit = authorization.isPermit,
                    decision = authorization.decision.name,
                    status = authorization.statusCode,
                    minimumAuthenticationLevel = authorization.minimumAuthenticationLevel,
                    minimumAuthenticationLevelOrg = authorization.minimumAuthenticationLevelOrg,
                ),
            )
        }
    }
}
