<div align="center">

# 🗝️ Fleks · Altinn PDP

**Kotlin library and REST service for asking Altinn whether a systembruker has access to a resource.**

<br/>

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)](https://gradle.org)
[![Ktor](https://img.shields.io/badge/Ktor-087CFA?style=flat-square&logo=ktor&logoColor=white)](https://ktor.io)
[![Altinn 3](https://img.shields.io/badge/Altinn-3-1E2B3C?style=flat-square)](https://docs.altinn.studio)
[![Maskinporten](https://img.shields.io/badge/Maskinporten-0069B4?style=flat-square)](https://docs.digdir.no/docs/Maskinporten/)
[![Status](https://img.shields.io/badge/status-in%20development-orange?style=flat-square)](#-about-the-project)

</div>

---

## 📖 Contents

- [🎯 About the project](#-about-the-project)
- [🧩 Modules](#-modules)
- [📦 Prerequisites](#-prerequisites)
- [🚀 Getting started](#-getting-started)
  - [Building the client](#building-the-client)
  - [Asking the PDP](#asking-the-pdp)
  - [Timeouts](#timeouts)
  - [Running the server](#running-the-server)
- [🔌 API](#-api)
- [🔑 Environment variables](#-environment-variables)
- [🌍 Environments](#-environments)
- [🧪 Testing](#-testing)
- [🔗 Useful links](#-useful-links)

---

## 🎯 About the project

A valid Maskinporten token proves that a systembruker belongs to the calling system. It does not
prove that the systembruker was ever granted access to any particular resource. There is
deliberately no link between the Maskinporten scope and the Altinn resource, so an API that only
validates the token has answered half the question.

The other half is a **PDP-oppslag**: asking Altinn's Policy Decision Point whether this
systembruker may act on behalf of this organisation, on this resource, in this way. That is what
this project is for.

> [!NOTE]
> A Kartverket API therefore makes two independent checks: it validates the token's scope itself,
> **and** it performs a PDP-oppslag. Neither one replaces the other.

---

## 🧩 Modules

| Module                                             | What it is                                                            | Published as                                                                  |
| :------------------------------------------------- | :-------------------------------------------------------------------- | :---------------------------------------------------------------------------- |
| [`altinn-pdp-client`](altinn-pdp-client)           | Kotlin library that talks to Maskinporten and the Altinn PDP directly | a package, for other services to depend on                                    |
| [`altinn-pdp-rest-server`](altinn-pdp-rest-server) | Ktor server exposing a simplified REST/JSON API over the client       | a Docker image, built with [Jib](https://github.com/GoogleContainerTools/jib) |

`altinn-pdp-rest-server` is the intended consumer of `altinn-pdp-client`, so other systems can ask
"is this allowed?" over plain JSON without speaking Maskinporten and XACML themselves.

---

## 📦 Prerequisites

- JDK 21, or let the Gradle toolchain resolver provision one
- A [Maskinporten client](https://docs.digdir.no/docs/Maskinporten/maskinporten_guide_apikonsument.html):
  a client id and its private key as JWK, granted `altinn:authorization/authorize`
- An Azure API Management subscription key for the Access Management products, ordered from
  Altinn servicedesk. Without it the gateway rejects the call with 401 before the PDP sees it

---

## 🚀 Getting started

```bash
./gradlew build
```

Builds and tests every module. This is also what CI runs.

### Building the client

```kotlin
val client = PdpClient.builder()
    .environment(AltinnEnvironment.TT02)
    .subscriptionKey("<subscription key>")
    .maskinportenClientId("<client id>")
    .maskinportenJwk(jwkJson)
    .timeouts(Timeouts.DEFAULT)
    .build()
```

Build one client and reuse it. Both the Maskinporten token and the Altinn token are cached and
fetched again shortly before they expire, and it is safe to call from several coroutines at once.

`tokenProvider(...)` replaces the two Maskinporten setters with an `AltinnTokenProvider` you
built yourself, which is handy in tests or to share one provider across several clients.

### Asking the PDP

```kotlin
val authorization = client.authorize(
    systemuserId = SystemUserId("<systembruker uuid>"),
    resourceId = ResourceId("<resource id>"),
    customerOrganizationNumber = OrganizationNumber("923609016"),
    action = ActionId("read"),
)
```

The answer is a `PdpAuthorization`:

| Member                          | What it is                                                                                                                                                 |
| :------------------------------ | :--------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `decision`                      | `PdpDecision`: `PERMIT`, `DENY`, `NOT_APPLICABLE` (no matching policy, not in itself an error) or `INDETERMINATE` (the PDP could not evaluate the request) |
| `isPermit`                      | Shorthand for `decision == PERMIT`                                                                                                                         |
| `obligations`                   | Every obligation Altinn attached, unfiltered, including ones this library does not model                                                                   |
| `minimumAuthenticationLevel`    | The `urn:altinn:minimum-authenticationlevel` obligation as an `Int`, or null                                                                               |
| `minimumAuthenticationLevelOrg` | The same for `urn:altinn:minimum-authenticationlevel-org`                                                                                                  |
| `statusCode`                    | Altinn's XACML status URN, or null                                                                                                                         |

> [!WARNING]
> A `PERMIT` that carries a `minimumAuthenticationLevel` is **conditional**. XACML expects
> whoever enforces the decision to honour the obligation, and this library cannot: it never sees
> your end user's token. Check the level yourself before acting on the permit, or pass it on to
> something that can.

> [!IMPORTANT]
> This is the org number of the customer the systembruker acts **on behalf of**, not your own.
> In the Maskinporten token it is `authorization_details[].systemuser_org`. It is **not** the
> `consumer` claim, which holds the vendor's org number. Strip the ISO6523 prefix: send
> `311718371`, not `0192:311718371`.

### Timeouts

Two values bound a lookup, and `PdpClient.builder()` **requires you to choose them** -
a library cannot know what call chain it has been dropped into, so it will not decide on your
behalf. `Timeouts.DEFAULT` carries the reference values below for a caller with no opinion yet,
but passing it is a deliberate act; `build()` fails if `timeouts(...)` was never called.

| Timeout   | `Timeouts.DEFAULT` | Bounds                                   |
| :-------- | :----------------- | :--------------------------------------- |
| `request` | 10 s               | one call end to end, connecting included |
| `total`   | 20 s               | a whole `authorize(...)` call            |

```kotlin
val client = PdpClient.builder()
    // ...
    .timeouts(Timeouts(request = ..., total = ...))
    .build()
```

`total` is the one that matters most. A lookup on cold caches fetches a Maskinporten token,
exchanges it and then calls the PDP, so without a budget across all three the worst case is three
request timeouts back to back. Exceeding it fails the call with a `PdpException`.

#### Choosing values

The rule: **a timeout must be shorter than the one it sits inside**, and the deeper into the call
chain you go, the shorter it gets. `request` < `total` < whatever your own caller allows you.

Getting this backwards is not merely untidy - it breaks four things at once:

- **Wasted work.** Your caller gives up first, but your call to Altinn keeps running, holding a
  connection and a thread to produce a result nobody will read.
- **No room to recover.** If the inner call may spend the entire budget, the layer above has
  nothing left for a retry, a fallback or even a tidy error.
- **Useless errors.** Time out first and you can answer `502` with a message saying which
  dependency stalled. Time out second and your caller sees an opaque client-side timeout while
  your own logs show a call that looked fine.
- **Cascading failure.** A slow dependency otherwise pins threads and connections at _every_
  layer simultaneously, turning one struggling service into a system-wide outage.

The general form of this is deadline propagation, as in [gRPC deadlines](https://grpc.io/docs/guides/deadlines/):
a deadline is an absolute point in time set by the original caller, and each hop passes on what is
_left_ of it rather than a fresh budget. Fixed, decreasing timeouts are the poor-man's version of
the same idea - and what this client offers today, since it takes no deadline from its caller.

#### Connecting

Connect timeouts belong to the `HttpClient`, which is the only place `java.net.http` keeps them
and the only place they can still be set once a client exists. The client this library builds for
you sets none, so connecting is bounded by `request`, which the JDK counts from before the
connection is made. To keep connecting on a shorter leash, build the client yourself:

```kotlin
val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

PdpClient.builder()
    // ...
    .httpClient(http)
    .timeouts(Timeouts(request = ..., total = ...))
    .build()
```

> [!IMPORTANT]
> Set `followRedirects(NEVER)` on any client you pass to `httpClient(...)`. These calls carry a
> client assertion and a bearer token, and a followed redirect would hand them to whatever host
> the redirect names. The client this library builds sets it for you; yours is your own.

#### What the server picks

`altinn-pdp-rest-server` is a consumer like any other, so it chooses explicitly rather than
inheriting the defaults above (`request` 4 s, `total` 8 s, both overridable per
deployment - see [Environment variables](#-environment-variables)). That leaves roughly **10 s**
as the response budget callers of `POST /authorize` should allow, so that a stalled Altinn comes
back to them as a `502` with a message rather than as a timeout of their own.

### Running the server

Create the ignored local secrets file from the committed template, then start Ktor:

```bash
cp .env.example .env
./scripts/dev.sh
```

The server runs on <http://localhost:8080>. `dev.sh` is what turns `.env` into real environment
variables - the server itself only ever reads the environment, so starting it any other way (an
IDE run configuration, `./gradlew :altinn-pdp-rest-server:run`) means setting them yourself.

---

## 🔌 API

### `POST /authorize`

Request body:

```json
{
  "systemuserId": "<systembruker id from the token's authorization_details>",
  "resourceId": "<resource identifier in the Altinn Resource Registry>",
  "customerOrganizationNumber": "923609016",
  "action": "read"
}
```

All four fields are required strings, and are validated before Altinn is called:

| Felt                         | Regel                                                |
| :--------------------------- | :--------------------------------------------------- |
| `systemuserId`               | UUID                                                 |
| `resourceId`                 | `^[a-z0-9_-]{4,}$`, the Resource Registry's own rule |
| `customerOrganizationNumber` | 9 digits with a valid MOD11 check digit              |
| `action`                     | Non-empty, no format constraint                      |

The Altinn subscription key and Maskinporten credentials are configured
server-side (see [Environment variables](#-environment-variables)) - callers never supply them.

Allow at least 10 seconds for a response, so a stalled Altinn reaches you as a `502` rather than
as a timeout of your own - see [Timeouts](#timeouts).

Response body (`200 OK`):

```json
{
  "permit": true,
  "decision": "PERMIT",
  "status": "urn:oasis:names:tc:xacml:1.0:status:ok",
  "minimumAuthenticationLevel": 3,
  "minimumAuthenticationLevelOrg": 3
}
```

`permit` is a boolean shorthand for `decision == "PERMIT"`.

`status`, `minimumAuthenticationLevel` and `minimumAuthenticationLevelOrg` are omitted when
Altinn sends nothing for them, so a response may still be just `permit` and `decision`.

`decision` is one of:

| Value            | Meaning                                                                      |
| :--------------- | :--------------------------------------------------------------------------- |
| `PERMIT`         | The systembruker is allowed to perform `action` on the resource for that org |
| `DENY`           | Explicitly denied                                                            |
| `NOT_APPLICABLE` | No matching policy - not necessarily an error                                |
| `INDETERMINATE`  | The PDP couldn't evaluate the request                                        |

#### Authentication level obligations

A `PERMIT` can be **conditional**. When Altinn attaches a minimum authentication level to the
decision, it arrives as `minimumAuthenticationLevel` (and `minimumAuthenticationLevelOrg` for the
organisation-level equivalent).

This service cannot check those levels. It never sees your token - that is the point of the
design - so it passes them to you instead. **A `PERMIT` carrying a level you have not met is not
a permit.** Before acting on one, confirm your own end user authenticated at that level or higher.
Callers that ignore these fields are trusting a condition nobody verified.

#### Telling "no" apart from "couldn't tell"

`status` is Altinn's XACML status URN. `...:status:ok` means the question was evaluated;
`...:status:processing-error` means it wasn't. This matters because both come back as
`permit: false`: a misspelled `resourceId` returns `INDETERMINATE` with a processing-error status,
which is a bug in the caller, not a denial. Branch on `status` if you need to tell them apart.

Error responses (any non-2xx) share one shape:

```json
{
  "error": "<human-readable summary>",
  "code": "<stable machine-readable code>"
}
```

Branch on `code`, never on `error`. `error` is prose and may be reworded; `code` is part of the
contract. A validation failure adds an `errors` array listing **every** field that failed, not
just the first:

```json
{
  "error": "Validation failed",
  "code": "VALIDATION_ERROR",
  "errors": [
    {
      "field": "customerOrganizationNumber",
      "code": "INVALID_FORMAT",
      "message": "customerOrganizationNumber must have a valid MOD11 check digit"
    },
    { "field": "action", "code": "MISSING", "message": "action is required" }
  ]
}
```

| `code`              | Status | Meaning                                                                          |
| :------------------ | :----- | :------------------------------------------------------------------------------- |
| `VALIDATION_ERROR`  | 400    | One or more fields failed validation. See `errors`                               |
| `MALFORMED_BODY`    | 400    | Not valid JSON, or a field of the wrong type                                     |
| `UPSTREAM_REJECTED` | 400    | Altinn itself answered 400 to the request we built                               |
| `UPSTREAM_ERROR`    | 502    | Calling Maskinporten or Altinn failed, including our own auth and quota problems |
| `INTERNAL_ERROR`    | 500    | Anything unanticipated                                                           |

Per-field `code` is `MISSING` (absent, null or blank) or `INVALID_FORMAT` (present but wrong shape).

| Status                      | Cause                                                                                                                                                                                               |
| :-------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `400 Bad Request`           | A field failed validation, the body was malformed, or Altinn itself answered 400. An unknown `resourceId` is _not_ a 400: Altinn answers `200` with `INDETERMINATE` and a processing-error `status` |
| `502 Bad Gateway`           | Calling Maskinporten or Altinn failed. This includes Altinn answering 401, 403 or 429, which are this service's credentials and quota, not the caller's problem                                     |
| `500 Internal Server Error` | Anything unanticipated                                                                                                                                                                              |

### `GET /health/live`

Liveness probe. Returns `200 OK` with an empty body if the server is up - not part of the stable
API.

---

## 🔑 Environment variables

| Variable                    | Required | Default |
| :-------------------------- | :------- | :------ |
| `MASKINPORTEN_CLIENT_ID`    | yes      | -       |
| `MASKINPORTEN_CLIENT_JWK`   | yes      | -       |
| `ALTINN_SUBSCRIPTION_KEY`   | yes      | -       |
| `ALTINN_ENVIRONMENT`        | no       | `TT02`  |
| `ALTINN_REQUEST_TIMEOUT_MS` | no       | `4000`  |
| `ALTINN_TOTAL_TIMEOUT_MS`   | no       | `8000`  |
| `ACCESS_LOG_ENABLED`        | no       | `true`  |

See `.env.example` for what each variable is and where to get it.

`altinn-pdp-rest-server/src/main/resources/application.yaml` maps each one onto a configuration
key via Ktor's `$ENV_VAR` substitution, so a missing required variable stops the server at
startup rather than at the first request. A JVM system property of the same name works too, which
is occasionally handier than an environment variable in an IDE.

Never commit `.env`, and never print secrets in logs.

---

## 🌍 Environments

`AltinnEnvironment` fixes, from one choice, every value that has to stay consistent across an
environment: the Altinn platform base URL and the Maskinporten token endpoint.

| Environment | Altinn platform                   | Maskinporten                         |
| :---------- | :-------------------------------- | :----------------------------------- |
| `TT02`      | `https://platform.tt02.altinn.no` | `https://test.maskinporten.no/token` |
| `PROD`      | `https://platform.altinn.no`      | `https://maskinporten.no/token`      |

The client also has raw base-URL constructors for pointing at a local test server.

---

## 🧪 Testing

```bash
./gradlew test
```

`./gradlew build` runs the tests as part of the build, and CI runs it on every pull request.

---

## 🔗 Useful links

| Resource                         | Link                                                                                    |
| :------------------------------- | :-------------------------------------------------------------------------------------- |
| Authorising a systembruker       | https://docs.altinn.studio/nb/authorization/guides/resource-owner/system-user/          |
| Altinn Studio documentation      | https://docs.altinn.studio                                                              |
| Altinn-delegering i Maskinporten | https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/delegering   |
| Systembruker                     | https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/systembruker |
| Maskinporten                     | https://docs.digdir.no/docs/Maskinporten                                                |
| Altinn TT02 (test)               | https://tt02.altinn.no                                                                  |

---

<div align="center">
<sub>Laget av <b>Fleks-Team Tilgangsstyring</b> i Bekk, for Kartverket</sub>
</div>
