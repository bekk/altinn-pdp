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

| Module | What it is | Published as |
| :--- | :--- | :--- |
| [`altinn-pdp-client`](altinn-pdp-client) | Kotlin library that talks to Maskinporten and the Altinn PDP directly | a package, for other services to depend on |
| [`altinn-pdp-rest-server`](altinn-pdp-rest-server) | Ktor server exposing a simplified REST/JSON API over the client | a Docker image, built with [Jib](https://github.com/GoogleContainerTools/jib) |

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
    .build()
```

Build one client and reuse it. Both the Maskinporten token and the Altinn token are cached and
fetched again shortly before they expire, and it is safe to call from several coroutines at once.

`tokenProvider(...)` replaces the three Maskinporten setters with an `AltinnTokenProvider` you
built yourself, which is handy in tests or to share one provider across several clients.

### Asking the PDP

```kotlin
val decision = client.authorize(
    systemuserId = "<systembruker uuid>",
    resourceId = "<resource id>",
    organizationNumber = "923609016",
    action = "read",
)
```

The answer is a `PdpDecision`: `PERMIT`, `DENY`, `NOT_APPLICABLE` (no matching policy, which is
not in itself an error) or `INDETERMINATE` (the PDP could not evaluate the request). Use
`isPermitted(...)` instead when a boolean is all you need.

> [!IMPORTANT]
> `organizationNumber` is the **customer's** plain Norwegian org number, the party whose access
> is being checked. It is not the vendor's, and not the ISO6523-prefixed form Maskinporten tokens
> use.

### Timeouts

Three separate values bound a lookup, and `PdpClient.builder()` **requires you to choose them** -
a library cannot know what call chain it has been dropped into, so it will not decide on your
behalf. `Timeouts.DEFAULT` carries the reference values below for a caller with no opinion yet,
but passing it is a deliberate act; `build()` fails if `timeouts(...)` was never called.

| Timeout | `Timeouts.DEFAULT` | Bounds |
| :--- | :--- | :--- |
| `connect` | 5 s | establishing the connection |
| `request` | 10 s | one call end to end, connecting included |
| `total` | 20 s | a whole `authorize(...)` call |

```kotlin
val client = PdpClient.builder()
    // ...
    .timeouts(Timeouts(connect = ..., request = ..., total = ...))
    .build()
```

`total` is the one that matters most. A lookup on cold caches fetches a Maskinporten token,
exchanges it and then calls the PDP, so without a budget across all three the worst case is three
request timeouts back to back. Exceeding it fails the call with a `PdpException`.

#### Choosing values

The rule: **a timeout must be shorter than the one it sits inside**, and the deeper into the call
chain you go, the shorter it gets. `connect` < `request` < `total` < whatever your own caller
allows you.

Getting this backwards is not merely untidy - it breaks four things at once:

- **Wasted work.** Your caller gives up first, but your call to Altinn keeps running, holding a
  connection and a thread to produce a result nobody will read.
- **No room to recover.** If the inner call may spend the entire budget, the layer above has
  nothing left for a retry, a fallback or even a tidy error.
- **Useless errors.** Time out first and you can answer `502` with a message saying which
  dependency stalled. Time out second and your caller sees an opaque client-side timeout while
  your own logs show a call that looked fine.
- **Cascading failure.** A slow dependency otherwise pins threads and connections at *every*
  layer simultaneously, turning one struggling service into a system-wide outage.

The general form of this is deadline propagation, as in [gRPC deadlines](https://grpc.io/docs/guides/deadlines/):
a deadline is an absolute point in time set by the original caller, and each hop passes on what is
*left* of it rather than a fresh budget. Fixed, decreasing timeouts are the poor-man's version of
the same idea - and what this client offers today, since it takes no deadline from its caller.

> [!NOTE]
> `connect` only applies to the client this library builds. `java.net.http.HttpClient` cannot be
> given a connect timeout after the fact, so a client passed to `httpClient(...)` keeps its own -
> set one on your builder if you want connecting kept on a short leash. Without it connecting is
> still bounded, just by the wider `request`, which the JDK counts from before the connection is
> made.

#### What the server picks

`altinn-pdp-rest-server` is a consumer like any other, so it chooses explicitly rather than
inheriting the defaults above (`connect` 2 s, `request` 4 s, `total` 8 s, all overridable per
deployment - see [Environment variables](#-environment-variables)). That leaves roughly **10 s**
as the response budget callers of `POST /authorize` should allow, so that a stalled Altinn comes
back to them as a `502` with a message rather than as a timeout of their own.

### Running the server

Create the ignored local secrets file from the committed template, then start Ktor:

```bash
cp .env.example .env
./scripts/dev.sh
```

The server runs on <http://localhost:8080>.

---

## 🔌 API

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
server-side (see [Environment variables](#-environment-variables)) - callers never supply them.

Allow at least 10 seconds for a response, so a stalled Altinn reaches you as a `502` rather than
as a timeout of your own - see [Timeouts](#timeouts).

Response body (`200 OK`):

```json
{
  "permit": true,
  "decision": "PERMIT"
}
```

`permit` is a boolean shorthand for `decision == "PERMIT"`.

`decision` is one of:

| Value | Meaning |
| :--- | :--- |
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
| :--- | :--- |
| `400 Bad Request` | Malformed/missing JSON fields, or Altinn rejected the request itself (e.g. unknown `resourceId`) |
| `502 Bad Gateway` | Calling Maskinporten or Altinn failed for a reason unrelated to this request's content |
| `500 Internal Server Error` | Anything unanticipated |

### `GET /health/live`

Liveness probe. Returns `200 OK` with an empty body if the server is up - not part of the stable
API.

---

## 🔑 Environment variables

| Variable | Required | Default |
| :--- | :--- | :--- |
| `MASKINPORTEN_CLIENT_ID` | yes | - |
| `MASKINPORTEN_CLIENT_JWK` | yes | - |
| `ALTINN_SUBSCRIPTION_KEY` | yes | - |
| `ALTINN_ENVIRONMENT` | no | `TT02` |
| `MASKINPORTEN_TOKEN_URL` | no | TT02's Maskinporten token endpoint |
| `ALTINN_CONNECT_TIMEOUT_MS` | no | `2000` |
| `ALTINN_REQUEST_TIMEOUT_MS` | no | `4000` |
| `ALTINN_TOTAL_TIMEOUT_MS` | no | `8000` |

See `.env.example` for what each variable is and where to get it.

Never commit `.env`, and never print secrets in logs.

---

## 🌍 Environments

`AltinnEnvironment` fixes, from one choice, every value that has to stay consistent across an
environment: the Altinn platform base URL and the Maskinporten token endpoint.

| Environment | Altinn platform | Maskinporten |
| :--- | :--- | :--- |
| `TT02` | `https://platform.tt02.altinn.no` | `https://test.maskinporten.no/token` |
| `PROD` | `https://platform.altinn.no` | `https://maskinporten.no/token` |

The client also has raw base-URL constructors for pointing at a local test server.

---

## 🧪 Testing

```bash
./gradlew test
```

`./gradlew build` runs the tests as part of the build, and CI runs it on every pull request.

---

## 🔗 Useful links

| Resource | Link |
| :--- | :--- |
| Authorising a systembruker | https://docs.altinn.studio/nb/authorization/guides/resource-owner/system-user/ |
| Altinn Studio documentation | https://docs.altinn.studio |
| Altinn-delegering i Maskinporten | https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/delegering |
| Systembruker | https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/systembruker |
| Maskinporten | https://docs.digdir.no/docs/Maskinporten |
| Altinn TT02 (test) | https://tt02.altinn.no |

---

<div align="center">
<sub>Laget av <b>Fleks-Team Tilgangsstyring</b> i Bekk, for Kartverket</sub>
</div>
