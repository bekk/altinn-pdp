package no.kartverket.altinnpdp.restserver

import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.openapi.plus
import kotlinx.serialization.json.Json

private val openApiJson = Json { prettyPrint = true }

private val apiInfo = OpenApiInfo(
    title = "Altinn PDP REST API",
    version = "v0",
    description = """
        Asks Altinn whether a system user or a person may perform an action on an Altinn resource, on behalf of an
        organisation.

        The token your API receives says who the caller is. It does not say whether they have access to your
        resource. That is what this API answers.

        ## How to use it

        1. Validate the token your API received, as you do today. This API never sees that token.
        2. Take the subject and the organisation from the token. For a system user (Maskinporten token), send
           `systemuserId`. For a person (Ansattporten token), send `pid`. Either way, also send
           `customerOrganizationNumber`.
        3. Call `POST /authorize` with those, your `resourceId` and the `action`.
        4. Grant access only if `permit` is `true` and your end user meets `minimumAuthenticationLevel`, when the
           response has one.
        5. Treat everything else as no access, including every error response.

        Set your client timeout to at least 10 seconds. If Altinn is slow, you then get a `502` instead of a
        timeout of your own.

        ## Authentication level

        A `PERMIT` can come with a `minimumAuthenticationLevel`. The permit then only holds if your end user logged
        in at that level or higher. This API cannot check that, so you must. Your user's level is:

        | End user | Level |
        | :-- | :-- |
        | A system user, or any other Maskinporten token | 3 |
        | A person whose Ansattporten token has `acr` `substantial` | 3 |
        | A person whose Ansattporten token has `acr` `high` | 4 |

        If your user's level is lower than `minimumAuthenticationLevel`, treat the answer as no access.

        ## How fresh the answer is

        This API may answer from a cache instead of asking Altinn every time. A cached answer can still say `PERMIT`
        for a while after the access was removed in Altinn.

        Send `Cache-Control: max-age=<seconds>` to say how old an answer you accept. Send `max-age=0`, or `no-cache`,
        when you need Altinn's answer right now, for example before a write. Without the header, the server's default
        applies. The server also has an upper limit, and never answers with anything older, whatever `max-age` you send.

        The `Age` header in the answer says how many seconds ago this API asked Altinn for the decision. It is
        usually `0` when this call asked Altinn, and a little more when Altinn was slow to answer.
    """.trimIndent(),
)

fun Application.openApiSpec(): String =
    openApiJson.encodeToString(OpenApiDoc(info = apiInfo) + plugin(RoutingRoot).getAllRoutes())
