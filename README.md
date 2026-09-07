# Altinn PDP

Tools for asking Altinn's Policy Decision Point (PDP) whether a systembruker has been delegated access to a resource - a question a valid Maskinporten token alone can't answer, since it only proves the systembruker belongs to the calling system, not that it was ever granted access to any particular resource.

## Modules

| Module | What it is | Published as |
| --- | --- | --- |
| [`altinn-pdp-client`](altinn-pdp-client) | Kotlin library that talks to Maskinporten and the Altinn PDP directly | a package, for other services to depend on |
| [`altinn-pdp-rest-server`](altinn-pdp-rest-server) | Ktor server exposing a simplified REST/JSON API over `altinn-pdp-client` | a Docker image (via [Jib](https://github.com/GoogleContainerTools/jib)) |

`altinn-pdp-rest-server` is the intended consumer of `altinn-pdp-client`, so other systems can ask "is this allowed?" over plain JSON without speaking Maskinporten/XACML themselves. It's still an early scaffold (Ktor's default routing) and doesn't call the PDP yet.

## Key concepts

- **The PDP question.** `PdpClient.authorize(systemuserId, organizationNumber, resourceId, action)` answers one question: can this systemuser act on behalf of this org, on this resource, this way? The answer is a `PdpDecision` - `PERMIT`, `DENY`, `NOT_APPLICABLE` (no matching policy, not necessarily an error), or `INDETERMINATE` (the PDP couldn't evaluate the request).
- **Environments.** `AltinnEnvironment.TT02` / `.PROD` fix the base URL for both the PDP call and the token exchange from one place. A raw base-URL constructor still exists for pointing at a local test server.
- **Two tokens, not one.** A Maskinporten token proves the calling system's identity, but Altinn doesn't accept it directly - it's exchanged for a separate Altinn token, which is what's actually sent to the PDP. Both are fetched and cached automatically.

## Getting started

Requires JDK 21 (or let the Gradle toolchain resolver provision one).

```
./gradlew build
```

Builds and tests every module - this is also what CI runs (see below).

To run `altinn-pdp-rest-server` locally, first create the ignored local secrets file from the
committed template:

```shell
cp .env.example .env
```

The template has no variables yet; they are added as the app starts reading them. Fill in `.env`
when it does, then start Ktor:

```shell
./scripts/dev.sh
```

Do not commit `.env` or print secrets in logs.

## CI

`.github/workflows/build.yml` runs `./gradlew build` on every PR and on push to `main`.
`.github/dependabot.yml` keeps dependencies and Actions up to date.
