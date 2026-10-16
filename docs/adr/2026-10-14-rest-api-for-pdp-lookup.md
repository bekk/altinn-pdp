# Offer Altinn PDP lookups as a shared REST API, not just a library

Status: accepted  
Deciders: Team Tilgangsstyring 2026 (Rolf, Herman, Lars, Christoffer)

## Context and Problem Statement

Ansattporten and Altinn Autorisasjon are on the brink of being rolled out across several teams at
Kartverket. Any API that accepts a Maskinporten or Ansattporten token and wants to know whether the
caller may act on a given resource must, in addition to validating the token itself, ask Altinn's
Policy Decision Point (PDP): is this system user or person allowed to do this, on behalf of this
organisation, on this resource? A valid token proves who is calling, not what they are allowed to
do.

Doing that lookup against Altinn directly is not simple. A consumer needs a Maskinporten client
with a registered JWK, must exchange the Maskinporten token for an Altinn token, must call Altinn's
Azure API Management gateway with a paid subscription key, and must build and parse an XACML
request/response in XML. Each PDP lookup also has a cost per call, billed through that APIM
subscription. We don't want every team at Kartverket to repeat this integration independently.

## Decision Drivers

* Several, mostly unrelated teams at Kartverket will need PDP lookups around the same time, not
  just one.
* The native Altinn integration (Maskinporten client, token exchange, XACML/XML) is complex enough
  that reimplementing it per team is wasteful and error-prone, especially around correct caching of
  tokens and not leaking national identity numbers into logs.
* Altinn PDP lookups cost money per call. Consumers calling Altinn independently cannot share a
  cache, so the same question ("can this system user do X on resource Y for org Z") gets billed
  once per consumer instead of once, however often it is actually asked.
* Not every consuming team runs on the JVM, or wants a Kotlin dependency and its transitive
  dependencies in their own service.
* Fewer services holding their own Maskinporten client id, private key and Altinn APIM
  subscription key is preferable, both operationally and from a security standpoint.

## Considered Options

* Document the Altinn integration and let every team implement it themselves.
* Publish only the `altinn-pdp-client` Kotlin library, for teams to embed directly in their own
  JVM services.
* Build a shared REST/JSON service (`altinn-pdp-rest-server`), on top of the client, that any team
  calls over HTTP regardless of language, fronted by caching.

## Decision Outcome

Chosen option: "Build a shared REST/JSON service on top of the client", because it is the only
option that lets teams outside the JVM ecosystem participate, and the only one that lets us cache
PDP answers across consumers instead of per consumer, which is where the cost and latency benefit
actually comes from. The Kotlin library is kept and published alongside it, for the REST server's
own use and for JVM teams who would rather embed the client directly.

### Positive Consequences

* Any team can ask "is this allowed?" over plain JSON, without speaking Maskinporten, Altinn's
  token exchange, or XACML/XML themselves.
* A cache shared across all consumers reduces the number of lookups actually billed by Altinn, and
  answers repeated questions faster than a fresh PDP round-trip.
* Only one service needs a Maskinporten client id, private key and Altinn APIM subscription key
  on behalf of all consumers, instead of one per consuming team.
* Bug fixes, security fixes (e.g. not logging a national identity number) and adaptation to future
  Altinn API changes happen in one place and benefit every consumer at once.

### Negative Consequences

* A new service to operate: its own availability, scaling and on-call now sit between a consumer
  and Altinn's actual answer.
* Caching authorization decisions needs care: a cached `PERMIT` must not outlive a real-world
  revocation by more than an agreed, acceptable margin. Cache design is a follow-up decision, not
  covered by this ADR.
* Centralizing in one service means a bug there affects every consuming team at once, rather than
  being contained to a single team's integration.

## Pros and Cons of the Options

### Every team implements the Altinn integration themselves

* Good, because there is no shared service to build or operate.
* Good, because each team can tune its own caching and error handling.
* Bad, because the Maskinporten + token exchange + XACML/XML integration is duplicated, with all
  of its edge cases, in every team that needs it.
* Bad, because every team needs its own Maskinporten client and Altinn APIM subscription key.
* Bad, because there is no way to share a cache across teams, so the same question is billed once
  per team instead of once overall.

### Kotlin library only (`altinn-pdp-client`)

* Good, because it solves the integration complexity once, for any JVM service that depends on it.
* Good, because each consumer still controls its own caching, retries and failure handling.
* Bad, because teams not on the JVM cannot use it without a rewrite in their own stack.
* Bad, because every consuming service still needs its own Maskinporten client, private key and
  Altinn APIM subscription key.
* Bad, because caching still happens per consumer, so the number of billed Altinn calls does not go
  down as more teams adopt it.

### Shared REST API (`altinn-pdp-rest-server`) on top of the library

* Good, because any team can call it over plain HTTP/JSON, regardless of language.
* Good, because a cache shared across consumers can actually reduce the number of calls billed by
  Altinn, instead of merely moving the same number of calls to a shared implementation.
* Good, because only this service needs Maskinporten and Altinn APIM credentials, not every
  consumer.
* Bad, because it adds an operated service, and therefore its own availability, to the critical
  path of every consumer's authorization check.
* Bad, because cache correctness (how fast a revoked `PERMIT` must stop being served) becomes a
  shared concern that needs an explicit, agreed answer.

## References

* [Altinn authorization API](https://docs.altinn.studio/authorization/) — the PDP API this project
  wraps.
* [Maskinporten](https://docs.digdir.no/docs/Maskinporten/maskinporten_guide_apikonsument.html) —
  the token issuer every consumer would otherwise need to integrate with directly.
* [README.md](../../README.md) — current modules, API and configuration of this project.
