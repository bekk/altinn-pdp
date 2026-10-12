<div align="center">

# 🗝️ Fleks · Altinn PDP

**Kotlin library and REST service for asking Altinn whether a system user or a person has access to a resource.**

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
  - [HTTP client](#http-client)
  - [Running the server](#running-the-server)
- [🔌 API](#-api)
- [🔑 Environment variables](#-environment-variables)
- [📝 Logging](#-logging)
- [🌍 Environments](#-environments)
- [🧪 Testing](#-testing)
- [🔗 Useful links](#-useful-links)

---

## 🎯 About the project

A valid Maskinporten token proves that a system user belongs to the calling system. It does not
prove that the system user was ever granted access to any particular resource. There is
deliberately no link between the Maskinporten scope and the Altinn resource, so an API that only
validates the token has answered half the question.

The other half is a **PDP lookup**: asking Altinn's Policy Decision Point whether this
system user may act on behalf of this organisation, on this resource, in this way. That is what
this project is for.

> [!NOTE]
> A Kartverket API therefore makes two independent checks: it validates the token's scope itself,
> **and** it performs a PDP lookup. Neither one replaces the other.

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
val client = PdpClient(
    environment = AltinnEnvironment.TT02,
    subscriptionKey = "<subscription key>",
    maskinportenClientId = "<client id>",
    maskinportenKey = MaskinportenKey.parse(jwkJson),
    httpClient = JavaPdpHttpClient(HttpClient.newHttpClient(), requestTimeout = Duration.ofSeconds(3)),
)
```

Build one client and reuse it. Both the Maskinporten token and the Altinn token are cached and
fetched again shortly before they expire, and it is safe to call from several coroutines at once.

### Asking the PDP

```kotlin
val authorization = client.authorize(
    subject = SystemUserId.parse("<system user uuid>"),
    resourceId = ResourceId.parse("<resource id>"),
    customerOrganizationNumber = OrganizationNumber.parse("923609016"),
    action = ActionId.parse("read"),
)
```

For a person, the subject is a `PersonId` instead. It prints as `PersonId(***********)`, so the
number never reaches a log through `toString`:

```kotlin
val authorization = client.authorize(
    subject = PersonId.parse("<national identity number or D number>"),
    resourceId = ResourceId.parse("<resource id>"),
    customerOrganizationNumber = OrganizationNumber.parse("923609016"),
    action = ActionId.parse("read"),
)
```

`PersonId.parse` only accepts real persons by default. Against TT02, set NoCommons'
`FodselsnummerValidator.ALLOW_SYNTHETIC_NUMBERS = true` at startup to accept Tenor test persons
too. The switch is global for the whole application, so the library leaves it alone.

The answer is a `PdpAuthorization`:

| Member                          | What it is                                                                                                                                                 |
| :------------------------------ | :--------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `decision`                      | `PdpDecision`: `PERMIT`, `DENY`, `NOT_APPLICABLE` (no matching policy, not in itself an error) or `INDETERMINATE` (the PDP could not evaluate the request) |
| `isPermit`                      | Shorthand for `decision == PERMIT`                                                                                                                         |
| `obligations`                   | Every obligation Altinn attached, unfiltered, including ones this library does not model                                                                   |
| `minimumAuthenticationLevel`    | The `urn:altinn:minimum-authenticationlevel` obligation as an `Int`, or null                                                                               |
| `minimumAuthenticationLevelOrg` | The same for `urn:altinn:minimum-authenticationlevel-org`. Altinn only applies it to its service owners, never to a system user or person                  |
| `statusCode`                    | Altinn's XACML status URN, or null                                                                                                                         |

> [!WARNING]
> A `PERMIT` that carries a `minimumAuthenticationLevel` is **conditional**: it only holds if your
> end user logged in at that level or higher, and the library cannot check that for you. A system
> user counts as level 3. A person with `acr` `substantial` in their Ansattporten token is level 3,
> and with `high` level 4.

> [!IMPORTANT]
> `customerOrganizationNumber` is the customer: the organisation the system user or person acts
> **on behalf of** when calling your API, not your own. For a system user it is
> `authorization_details[].systemuser_org` in the Maskinporten token, **not** the `consumer`
> claim, which holds the vendor's org number. For a person it is the organisation they chose when
> logging in, `authorization_details[].authorized_parties[].orgno.ID` in the Ansattporten token.
> Strip the ISO6523 prefix: send `311718371`, not `0192:311718371`.

### HTTP client

`httpClient` takes a `PdpHttpClient`, and timeouts, proxy and so on are set on it. Implement it
on the HTTP client you already use, or use `JavaPdpHttpClient`:

```kotlin
JavaPdpHttpClient(
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    requestTimeout = Duration.ofSeconds(3),
)
```

> [!IMPORTANT]
> A `PdpHttpClient` must not follow redirects, since the calls carry tokens, and must throw an
> `IOException` when a call fails. OkHttp and Ktor client follow redirects by default, so turn
> that off if you build on one of them.

The server connects within 2 s and waits at most 3 s per call. A lookup makes at most three
calls, so callers of `POST /authorize` should allow 10 s.

### Running the server

Create the ignored local secrets file from the committed template, then start Ktor:

```bash
cp .env.example .env
./scripts/dev.sh
```

The server runs on <http://localhost:8080>, or on the port in `PORT`. `dev.sh` is what turns
`.env` into real environment variables - the server itself only ever reads the environment, so
starting it any other way (an IDE run configuration, `./gradlew :altinn-pdp-rest-server:run`)
means setting them yourself.

---

## 🔌 API

The REST server's API is documented in its OpenAPI spec. It covers every field, decision and
error code, and how to act on the answer.

| Endpoint           | What it is                                                      |
| :----------------- | :-------------------------------------------------------------- |
| `POST /authorize`  | Asks whether a system user or a person has access to a resource |
| `GET /openapi`     | The OpenAPI spec as JSON                                        |
| `GET /health/live` | Liveness probe for the platform, left out of the spec           |

The spec is built from the routes themselves, so it cannot describe an API the server does not
serve. It names no `servers`, so a client uses the host it fetched the spec from. The same spec is
checked in as [`altinn-pdp-rest-server/openapi.json`](altinn-pdp-rest-server/openapi.json).
Regenerate it after changing the API:

```bash
./gradlew :altinn-pdp-rest-server:generateOpenApiSpec
```

The build fails if the checked-in file is stale, so there is no way to forget.

---

## 🔑 Environment variables

| Variable                  | Required | Default |
| :------------------------ | :------- | :------ |
| `MASKINPORTEN_CLIENT_ID`  | yes      | -       |
| `MASKINPORTEN_CLIENT_JWK` | yes      | -       |
| `ALTINN_ENVIRONMENT`      | yes      | -       |
| `ALTINN_SUBSCRIPTION_KEY` | yes      | -       |
| `ACCESS_LOG_ENABLED`      | no       | `true`  |
| `LOGBACK_CONFIG_FILE`     | no       | -       |
| `APPLICATION_CONFIG_FILE` | no       | -       |
| `PORT`                    | no       | `8080`  |

See `.env.example` for what each variable is and where to get it.

`altinn-pdp-rest-server/src/main/resources/application.yaml` maps each one onto a configuration
key via Ktor's `$ENV_VAR` substitution, so a missing required variable stops the server at
startup rather than at the first request. A JVM system property of the same name works too, which
is occasionally handier than an environment variable in an IDE.

To replace the bundled `application.yaml` altogether, point `APPLICATION_CONFIG_FILE` at your own
`.yaml` or `.yml` file. It replaces the bundled one rather than merging with it, so it needs every
key the bundled one has. The server refuses to start if that path is not a file.

Never commit `.env`, and never print secrets in logs.

---

## 📝 Logging

The server logs to stdout through logback, set up by the bundled
`altinn-pdp-rest-server/src/main/resources/logback.xml`. To replace it, mount your own file and
point `LOGBACK_CONFIG_FILE` at it. The server refuses to start if that path is not a file.

Access log lines use the logger `access`, so your own file can send them somewhere else, or format
them differently, from the rest:

```xml
<logger name="access" additivity="false">
    <appender-ref ref="ACCESS"/>
</logger>
```

---

## 🌍 Environments

`environment` decides which Altinn and Maskinporten the client talks to:

| Environment | Altinn platform                   | Maskinporten                         |
| :---------- | :-------------------------------- | :----------------------------------- |
| `TT02`      | `https://platform.tt02.altinn.no` | `https://test.maskinporten.no/token` |
| `PROD`      | `https://platform.altinn.no`      | `https://maskinporten.no/token`      |

The REST server accepts a synthetic `pid`, such as a Tenor test person, only when
`ALTINN_ENVIRONMENT` is `TT02`. In `PROD`, only real persons pass.

---

## 🧪 Testing

```bash
./gradlew test
```

`./gradlew build` runs the tests as part of the build, and CI runs it on every pull request.

---

## 🔗 Useful links

| Resource                          | Link                                                                                                                |
| :-------------------------------- | :------------------------------------------------------------------------------------------------------------------ |
| Authorising a system user         | https://docs.altinn.studio/nb/authorization/guides/resource-owner/system-user/                                      |
| Authorising a person              | https://docs.altinn.studio/nb/authorization/guides/resource-owner/generic-access-resource/integrating-link-service/ |
| Altinn Studio documentation       | https://docs.altinn.studio                                                                                          |
| Altinn delegation in Maskinporten | https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/delegering                               |
| System user                       | https://skip.kartverket.no/docs/tilgangsstyring/valg-av-identitetstilbyder/systembruker                             |
| Maskinporten                      | https://docs.digdir.no/docs/Maskinporten                                                                            |
| Ansattporten                      | https://docs.digdir.no/docs/ansattporten/ansattporten_om.html                                                       |
| Altinn TT02 (test)                | https://tt02.altinn.no                                                                                              |

---

<div align="center">
<sub>Made by <b>Fleks-Team Tilgangsstyring</b> at Bekk, for Kartverket</sub>
</div>
