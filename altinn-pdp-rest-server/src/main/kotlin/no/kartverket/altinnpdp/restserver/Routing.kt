package no.kartverket.altinnpdp.restserver

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.kartverket.altinnpdp.client.PdpClient

fun Application.configureRouting() {
    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK)
        }

        post("/authorize") {
            val pdpClient: PdpClient by dependencies
            val request = call.receive<AuthorizeRequest>()
            request.requireValidOrganizationNumber()
            val decision = pdpClient.authorize(
                systemuserId = request.systemuserId,
                resourceId = request.resourceId,
                organizationNumber = request.organizationNumber,
                action = request.action,
            )

            call.respond(AuthorizeResponse(permit = decision.isPermit, decision = decision.name))
        }
    }
}
