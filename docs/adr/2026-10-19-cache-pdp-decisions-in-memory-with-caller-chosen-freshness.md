# Cache PDP decisions in memory per pod, with freshness chosen by the caller

Status: proposed, partially superseded by [Share the decision cache between pods with Redis, as an option](2026-10-27-share-the-decision-cache-between-pods-with-redis.md)  
Deciders: Team Tilgangsstyring

## Context and Problem Statement

[Offer Altinn PDP lookups as a shared REST API](2026-10-14-rest-api-for-pdp-lookup.md) gave a
shared cache as one reason for the service: each PDP lookup is billed, and repeated questions are
answered faster from a cache than by a round-trip to Altinn. That ADR left the cache design to a
follow-up decision, together with how long a cached `PERMIT` may outlive a real-world revocation.

Caching an authorization decision means that, for a while, a caller can be told `PERMIT` for
access that has already been revoked in Altinn. How should `altinn-pdp-rest-server` cache
decisions, and who decides how stale an answer is acceptable?

## Decision Drivers

- A stale `PERMIT` is a security risk, and how much of it is acceptable depends on what the caller
  is protecting. One team may accept a minute; another wants Altinn's answer every time.
- Fewer billed lookups and lower latency for callers that repeat the same question.
- The service should stay simple to operate, with no new infrastructure to run.
- Existing callers must not start getting cached answers just because the server is upgraded.

## Considered Options

- An in-memory Caffeine cache in each pod, with freshness chosen per request through Cache-Control
- A shared cache across pods, such as Redis or Valkey
- A server-wide time to live only, with no say for the caller
- No cache

## Decision Outcome

Chosen option: "An in-memory Caffeine cache in each pod, with freshness chosen per request through
Cache-Control", because it puts the trade-off between staleness and cost in the hands of the caller
that bears the risk, keeps an upper bound the server controls, and needs no new infrastructure.

- The cache is off unless `DECISION_CACHE_ENABLED` is set, and holds at most
  `DECISION_CACHE_MAXIMUM_SIZE` decisions.
- A caller sends `Cache-Control: max-age=<seconds>` to say how old a decision it accepts, or
  `no-cache` (or `max-age=0`) to have Altinn asked and the cached entry refreshed. An invalid
  `max-age` is refused with `400` and a `validation-error` with `Cache-Control` in `errors`, so a
  typo never silently gives a looser answer than the caller meant.
- A caller that sends neither gets `DECISION_CACHE_DEFAULT_MAX_AGE_SECONDS`, which defaults to 0.
  Callers only get cached answers if they, or whoever runs the server, ask for them.
- `DECISION_CACHE_TIME_TO_LIVE_SECONDS` (default 60) is the upper bound, whatever `max-age` a
  caller sends. **This is the accepted stale-`PERMIT` window: a revoked access can be answered
  `PERMIT` for at most this long after Altinn last said `PERMIT`.**
- The response carries an `Age` header saying how old the decision is, counted from when the server
  asked Altinn, since Altinn may have decided at any moment after that.
- `INDETERMINATE` decisions and failed lookups are not cached.
- Concurrent lookups of the same question share one call to Altinn when that call started within
  the caller's `max-age`. A `max-age=0` request therefore always makes its own call, and never gets
  the answer to a call that started before it, which may predate a change in Altinn.

### Positive Consequences

- Each caller chooses its own staleness, so one service can serve both a caller that wants Altinn's
  answer every time and one that is happy with a minute-old decision.
- The worst case is bounded by a single setting the server's operators control.
- Upgrading the server changes nothing for existing callers, since the default max age is 0.
- Nothing new to run: the cache lives in the server's own memory.

### Negative Consequences

- Each pod has its own cache. With several pods, the same question is billed once per pod, and
  `no-cache` refreshes only the pod that handled the request. Another pod may still answer from an
  older entry, within the caller's `max-age` and the time to live.
- The cache is lost on every restart and deploy.
- Callers that want caching must send a Cache-Control header, or the server must be run with a
  non-zero default max age.
- The time to live caps the benefit: no answer older than it can ever be served, even to a caller
  that would accept one.

## Pros and Cons of the Options

### An in-memory Caffeine cache in each pod, with freshness chosen per request through Cache-Control

- Good, because the caller that carries the risk of a stale `PERMIT` decides how much of it to
  accept.
- Good, because Cache-Control `max-age` and `no-cache` are standard HTTP that callers' clients and
  developers already know, and the `Age` response header comes with them.
- Good, because Caffeine is a mature, widely used JVM cache with size bounds and expiry, and a
  pluggable ticker that makes expiry testable without waiting.
- Good, because it needs no new infrastructure.
- Bad, because pods do not share entries, which lowers the hit rate and makes `no-cache` refresh
  only one pod.

### A shared cache across pods, such as Redis or Valkey

- Good, because every pod shares one hit rate, and `no-cache` refreshes the entry for all of them.
- Good, because the cache survives restarts and deploys.
- Bad, because it is another service to run, secure and monitor, and one more thing that can be
  down between a caller and an answer.
- Bad, because it stores authorization decisions, keyed by national identity numbers and system
  users, outside the service's own memory.

### A server-wide time to live only, with no say for the caller

- Good, because it is the simplest to build and to explain.
- Bad, because one staleness has to suit every caller, so it is either too strict for callers that
  only want to save cost or too loose for callers that need Altinn's current answer.

### No cache

- Good, because there is never a stale `PERMIT`.
- Bad, because every lookup is billed and pays a full round-trip to Altinn, which drops one of the
  reasons the shared service was made.

## References

- Follows up [Offer Altinn PDP lookups as a shared REST API](2026-10-14-rest-api-for-pdp-lookup.md)
- [RFC 9111 §5.2, Cache-Control](https://www.rfc-editor.org/rfc/rfc9111#section-5.2)
