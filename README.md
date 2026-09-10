# Altinn PDP

Tools for asking Altinn's Policy Decision Point (PDP) whether a systembruker has been delegated access to a resource - a question a valid Maskinporten token alone can't answer, since it only proves the systembruker belongs to the calling system, not that it was ever granted access to any particular resource.

## Modules

| Module | What it is | Published as |
| --- | --- | --- |
| [`altinnpdp-client`](altinnpdp-client) | Kotlin library that talks to Maskinporten and the Altinn PDP directly | a package, for other services to depend on |
| [`altinnpdp-restserver`](altinnpdp-restserver) | Ktor server exposing a simplified REST/JSON API over `altinnpdp-client` | a Docker image (via [Jib](https://github.com/GoogleContainerTools/jib)) |

`altinnpdp-restserver` is the intended consumer of `altinnpdp-client`, so other systems can ask "is this allowed?" over plain JSON without speaking Maskinporten/XACML themselves.

## Key concepts

- **The PDP question.** `PdpClient.authorize(systemuserId, organizationNumber, resourceId, action)` answers one question: can this systemuser act on behalf of this org, on this resource, this way? The answer is a `PdpDecision` - `PERMIT`, `DENY`, `NOT_APPLICABLE` (no matching policy, not necessarily an error), or `INDETERMINATE` (the PDP couldn't evaluate the request).
- **Environments.** `AltinnEnvironment.TT02` / `.PROD` fix the base URL for both the PDP call and the token exchange from one place. A raw base-URL constructor still exists for pointing at a local test server.
- **Two tokens, not one.** A Maskinporten token proves the calling system's identity, but Altinn doesn't accept it directly - it's exchanged for a separate Altinn token, which is what's actually sent to the PDP. Both are fetched and cached automatically.

## API (`altinnpdp-restserver`)

### `POST /authorize`

Request body:

```json
{
  "systemuserId": "<systembruker id from the token's authorization_details>",
  "resourceId": "<resource identifier in the Altinn Resource Registry>",
  "organizationNumber": "923609016",
  "action": "read"
}
```

All four fields are required strings. `organizationNumber` is the plain Norwegian org number
(no ISO6523 prefix). The Altinn subscription key and Maskinporten credentials are configured
server-side (see [Getting started](#getting-started)) - callers never supply them.

Response body (`200 OK`):

```json
{
  "decision": "PERMIT"
}
```

`decision` is one of:

| Value | Meaning |
| --- | --- |
| `PERMIT` | The systembruker is allowed to perform `action` on the resource for that org |
| `DENY` | Explicitly denied |
| `NOT_APPLICABLE` | No matching policy - not necessarily an error |
| `INDETERMINATE` | The PDP couldn't evaluate the request |

Error responses (any non-2xx) share one shape:

```json
{
  "error": "<human-readable message>"
}
```

| Status | Cause |
| --- | --- |
| `400 Bad Request` | Malformed/missing JSON fields, or Altinn rejected the request itself (e.g. unknown `resourceId`) |
| `502 Bad Gateway` | Calling Maskinporten or Altinn failed for a reason unrelated to this request's content |
| `500 Internal Server Error` | Anything unanticipated |

### `GET /`

Returns `200 OK` with a plain-text placeholder body - not part of the stable API, only useful as
a liveness check for now.

## Getting started

Requires JDK 21 (or let the Gradle toolchain resolver provision one).

```
./gradlew build
```

Builds and tests every module - this is also what CI runs (see below).

To run `altinnpdp-restserver` locally, first create the ignored local secrets file from the
committed template:

```shell
cp .env.example .env
```

Fill in `.env` (see `.env.example` for what each variable is and where to get it):

| Variable | Required | Default |
| --- | --- | --- |
| `MASKINPORTEN_CLIENT_ID` | yes | - |
| `MASKINPORTEN_CLIENT_JWK` | yes | - |
| `ALTINN_SUBSCRIPTION_KEY` | yes | - |
| `ALTINN_ENVIRONMENT` | no | `TT02` |
| `MASKINPORTEN_TOKEN_URL` | no | TT02's Maskinporten token endpoint |

Then start Ktor:

```shell
./scripts/dev.sh
```

Do not commit `.env` or print secrets in logs.

## CI

`.github/workflows/build.yml` runs `./gradlew build` on every PR and on push to `main`.
`.github/dependabot.yml` keeps dependencies and Actions up to date.
