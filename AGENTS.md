# AGENTS.md

Kotlin library and Ktor REST service that ask Altinn's Policy Decision Point (PDP) whether a system user or a
person has access to a resource. See [README.md](README.md) for the background, usage and configuration.

## Modules

- `altinn-pdp-client`: the library. It talks to Maskinporten and the Altinn PDP. It uses Kotlin's explicit API
  mode and is meant for other teams, so keep its public API small and deliberate.
- `altinn-pdp-rest-server`: a Ktor server that offers a simple JSON API on top of the client.

## Commands

- `./gradlew check` runs the tests and ktlint. It must pass before a change is done.
- `./gradlew ktlintFormat` fixes most lint errors.
- `./gradlew :altinn-pdp-rest-server:generateOpenApiSpec` rewrites `openapi.json` after an API change.
- `scripts/dev.sh` runs the server locally, using a `.env` copied from `.env.example`.

## Server conventions

- `main()` handles process setup: config, logging, then starting the server. `Application.module()` installs the
  plugins and routes. Keep that wiring in `module()`, so that tests and production run the same code.
- Server tests start the app with `testServer(...)` from `TestServer.kt`, which runs `module(...)` with a fake HTTP
  client instead of the real Altinn.
- All configuration is read once, into `AppConfig`. A new setting belongs in `application.yaml`, `.env.example`
  and the environment variable table in the README.
- The OpenAPI spec is generated from the routes, the `@JsonSchema` annotations on the models, and `OpenApi.kt`. A
  test fails if the checked-in `openapi.json` is out of date.
- The error `code` values and the response fields are a contract with callers. Change them only on purpose.
- Never log request bodies, national identity numbers or tokens.

## Decisions (ADRs)

Important decisions are recorded as Architecture Decision Records in `docs/adr`. Examples are choosing a library or
framework, changing the API contract, and security-relevant choices. Read the relevant ADRs before changing what they
cover. When a change makes a decision like this, suggest an ADR, but don't write one unless asked.

There are two templates. Pick the one that fits how much is at stake, or ask for guidance if unsure:

- **Brief** (`template-brief.md`): the decision is easy to reverse, or the best option is fairly obvious. A few
  sentences each for the context, the decision and the consequences are enough.
- **Detailed** (`template-detailed.md`): the decision is costly to reverse, affects callers or other teams, or real
  alternatives were weighed. It needs the full problem statement, the options considered with their pros and cons,
  and why the chosen option won. Ask for guidance with regards to list of deciders (individuals or teams who contributed
  to the decision).

Name files `YYYY-MM-DD-title.md`, using the date of the decision and a lowercase, hyphenated title. Never change or
rewrite an accepted ADR, other than updating status if it is superseded. Write a new one that supersedes it, and update
the old one's status.
