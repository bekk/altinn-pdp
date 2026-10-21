# Use the outgoing HTTP client interface from maskinporten-management, as a copy

Status: proposed  
Deciders: Team Tilgangsstyring

## Context and Problem Statement

`altinn-pdp-client` does not choose an HTTP client for its users. It takes an implementation of
its own small interface, and ships one built on `java.net.http.HttpClient`. Until now that
interface was `PdpHttpClient`, which sends a `PdpHttpRequest` with the method as a string and the
body as a string, and gets back a `PdpHttpResponse` with only a status code and a string body.

`maskinporten-management-client`, another library from the same team, solves the same problem with
`OutgoingHttpClient`. It has an `HttpMethod` enum, bodies as bytes, and the response's
`Content-Type`. Logging and test helpers are about to be built on that interface. Which interface
should `altinn-pdp-client` offer its users, and how should it relate to the one in
`maskinporten-management-client`?

## Decision Drivers

- Teams that use both libraries should implement the same interface, in the same way, for both.
- Helpers for logging and tests should be written once and work the same way in both libraries.
- The interface should handle responses that are not UTF-8 text, and say what type they are.
- The public API of `altinn-pdp-client` should stay small and deliberate, and the library should
  pull in as few dependencies as possible.
- Calls carry tokens, so the guards against following redirects must stay.

## Considered Options

- Copy `OutgoingHttpClient` from `maskinporten-management-client`, with only the HTTP methods this
  library uses
- Keep `PdpHttpClient`
- Depend on `maskinporten-management-client`, or a shared library extracted from it, for the
  interface
- Take a `java.net.http.HttpClient` directly, with no interface of our own

## Decision Outcome

Chosen option: "Copy `OutgoingHttpClient` from `maskinporten-management-client`, with only the HTTP
methods this library uses", because it gives both libraries the same shape for the interface,
handles binary responses, and adds no dependency.

- `PdpHttpClient`, `PdpHttpRequest` and `PdpHttpResponse` are replaced by `OutgoingHttpClient`,
  `OutgoingRequest` and `OutgoingResponse`, in `no.kartverket.altinnpdp.client.http`.
- `JavaPdpHttpClient` is renamed to `JavaOutgoingHttpClient`. It still refuses an `HttpClient` that
  follows redirects and a request timeout that is not positive.
- `HttpMethod` has only `GET` and `POST`. Only the library builds an `OutgoingRequest`, since its
  constructor is `internal`, and it uses no other methods.

### Positive Consequences

- Teams that use both libraries implement the same shape of interface for each, and the README
  gives the same advice for both.
- Logging and test helpers can be written for one library and copied to the other with little more
  than a change of package.
- Responses keep their bytes and `Content-Type`, so the interface does not assume every response is
  UTF-8 text.
- No new dependency for users of `altinn-pdp-client`.

### Negative Consequences

- This is a breaking change. Users who implement `PdpHttpClient` or build a `JavaPdpHttpClient` must
  move to the new names, and implementations must now pass bodies as bytes and fill in
  `contentType`.
- The two interfaces have the same shape but are different types, in different packages. One
  implementation cannot be passed to both libraries without an adapter.
- The copies can drift apart. They already differ in `HttpMethod`, and a change to one has to be
  made in the other by hand.
- Adding a value to `HttpMethod` later breaks users who match on it with an exhaustive `when`.

## Pros and Cons of the Options

### Copy `OutgoingHttpClient` from `maskinporten-management-client`, with only the HTTP methods this library uses

- Good, because both libraries offer the same shape of interface, so helpers and advice carry over.
- Good, because it handles binary responses and their `Content-Type`.
- Good, because it adds no dependency.
- Bad, because it breaks existing users of `PdpHttpClient`.
- Bad, because the two copies are separate types that must be kept in step by hand.

### Keep `PdpHttpClient`

- Good, because existing users need not change anything.
- Bad, because string bodies assume every response is UTF-8 text, and the response type is lost.
- Bad, because logging and test helpers would have to be written twice, for two differently shaped
  interfaces.

### Depend on `maskinporten-management-client`, or a shared library extracted from it, for the interface

- Good, because there is one type, so one implementation works with both libraries and the copies
  cannot drift.
- Bad, because a dependency on `maskinporten-management-client` would bring in a whole client that
  users of `altinn-pdp-client` do not need.
- Bad, because a shared library is one more artifact to publish, version and keep compatible, for an
  interface of three small types.

### Take a `java.net.http.HttpClient` directly, with no interface of our own

- Good, because it is the smallest public API, with no HTTP types of our own.
- Bad, because users of OkHttp, Ktor client or another HTTP client could no longer use theirs, and
  would lose the timeouts, proxy and other settings they have set on it.
- Bad, because tests would need a real HTTP server instead of a fake client.

## References

- `OutgoingHttpClient` in `maskinporten-management-client`, package
  `no.kartverket.maskinportenmanagement.client.http`
