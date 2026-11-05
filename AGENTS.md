# AGENTS.md

Kotlin library and Ktor REST service that ask Altinn's Policy Decision Point (PDP) whether a system user or a
person has access to a resource. See [README.md](README.md) for the background, usage and configuration.

## Modules

- `altinn-pdp-client`: the library. It talks to Maskinporten and the Altinn PDP. It uses Kotlin's explicit API
  mode and is meant for other teams, so keep its public API small and deliberate. The public API is in `client`
  and `client.http`. The rest is in `client.internal`: `maskinporten`, `token` and `xacml`.
- `altinn-pdp-rest-server`: a Ktor server that offers a simple JSON API on top of the client. The code is grouped by
  feature: `authorize` (with `authorize.cache`), `problem`, `observability` and `config`. `Main.kt` only wires them
  together.

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
  and the environment variable table in the README. The Logback path (`logback.configFile`) is the exception:
  `main()` sets it before `AppConfig.load(...)`, since Logback only reads it when the first logger is created, and
  ignores it after that without an error.
- NoCommons' `FodselsnummerValidator.ALLOW_SYNTHETIC_NUMBERS` is a static flag for the whole JVM. Only the server
  sets it, once at startup in `configurePidValidation`: on for `TT02`, off for `PROD`. The client only checks that a
  pid has 11 digits, so it never depends on the flag. Organisation numbers are checked with MOD11 only, in both
  environments, so Tenor organisations pass in `PROD` too. NoCommons does not yet handle the check digit rule for
  numbers issued from 2032.
- The OpenAPI spec is generated from the routes, the `@JsonSchema` annotations on the models, `OpenApi.kt` and
  each route's operation, like `AuthorizeOpenApi.kt`. A test fails if the checked-in `openapi.json` is out of date.
- Errors follow Problem Details (RFC 9457), as `application/problem+json`. Build them with `ErrorResponse.of(...)`:
  `type` is a `tag:kartverket.no,2026:` name for each kind of error, from `ProblemType`, and `title` comes from the
  type. Leave out `detail` when the title says it all.
- The `type` names and the response fields are a contract with callers. Change them only on purpose.
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
