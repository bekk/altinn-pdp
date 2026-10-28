# Share the decision cache between pods with Redis, as an option

Status: proposed  
Deciders: Team Tilgangsstyring

## Context and Problem Statement

[Cache PDP decisions in memory per pod, with freshness chosen by the caller](2026-10-19-cache-pdp-decisions-in-memory-with-caller-chosen-freshness.md)
chose a Caffeine cache in each pod and turned down a shared cache such as Redis, mainly because it
is another service to run and because it stores authorization decisions outside the service.

That ADR also listed what the in-memory cache costs when the server runs with several pods:

- The same question is billed once per pod, so the hit rate drops with every pod added.
- `no-cache` refreshes only the pod that handled the request. Another pod may still answer from an
  older entry.
- The cache is lost on every restart and deploy.

The server is to run as two pods on Kubernetes, next to a single Redis pod without persistence.
That Redis will be down now and then, when it restarts or moves between nodes, and comes back
empty. Should the server share its cache between pods through it, and what should it do while
Redis is down?

This ADR replaces only the choice of where the cache lives. The rest of the 19.10 decision still
stands: the caller chooses freshness with `Cache-Control: max-age` or `no-cache`, the time to live
caps every `max-age`, the default max age is 0, the response carries an `Age` header, and
`INDETERMINATE` decisions and failed lookups are not cached.

## Decision Drivers

- Fewer billed lookups when the server runs with several pods, and a `no-cache` refresh that every
  pod sees.
- A cache that is down must not stop callers from getting an answer, unless the operators choose
  otherwise, and must slow them down by no more than a short, configurable timeout.
- National identity numbers and other values from the request must not be stored outside the
  service.
- A stale `PERMIT` must stay bounded by the time to live, as in the 19.10 ADR.
- Simple to build and run for now. More can be added when the bill or measurements ask for it.
- Existing deployments must not change, and must not need Redis, just because the server is
  upgraded.

## Considered Options

- Redis as an optional cache-aside cache, chosen with `DECISION_CACHE_TYPE`, next to the in-memory
  cache
- Redis replacing the in-memory cache
- Keep only the in-memory cache, and run fewer pods or route the same question to the same pod

## Decision Outcome

Chosen option: "Redis as an optional cache-aside cache, chosen with `DECISION_CACHE_TYPE`, next to
the in-memory cache", because it gives the pods one shared cache, keeps the questions themselves out
of Redis, and changes nothing for operators who do not ask for it.

- `DECISION_CACHE_TYPE=REDIS` keeps decisions in Redis. `IN_MEMORY` stays the default, and the
  cache as a whole is still off unless `DECISION_CACHE_ENABLED` is set.
- Cache-aside: a lookup reads Redis, and on a miss asks Altinn and saves the answer with `SET`, to
  expire after the time to live.
- Redis only sees an HMAC-SHA256 of the question, keyed with `DECISION_CACHE_REDIS_KEY_SECRET`, and
  prefixed with the Altinn environment, so TT02 and PROD never share a decision even in one Redis.
  The stored value is the decision, its status code, its obligations and when Altinn was asked.
  The server refuses to start with a secret shorter than 32 bytes, since there are few enough
  national identity numbers that anyone who can read Redis could try them all against a weak one.
- `DECISION_CACHE_REDIS_FAILURE_MODE` chooses what happens when Redis cannot be read. `BYPASS`, the
  default, asks Altinn without the cache. `REJECT` refuses the call with `503` and the new problem
  type `tag:kartverket.no,2026:decision-cache-unavailable`. This adds a response to the API
  contract, which callers only see when the operators choose `REJECT`. A call with `max-age=0` never
  reads the cache, so it is asked of Altinn in both modes. A failed save never fails the call, in
  either mode.
- Nothing is kept to write to Redis once it is back: a Redis that restarted is empty, and the next
  miss saves a fresh answer.
- Every wait for Redis is bounded by `DECISION_CACHE_REDIS_TIMEOUT_MILLIS` (default 100): each
  command, the socket connect, and the handshake after it. A lookup reads, and on a miss saves, so
  it waits for Redis at most twice the timeout, or up to twice more when it has to connect first.
  A save connects first if no read has, so a refresh from a pod that only gets `max-age=0` or
  `no-cache` calls still reaches the others. A lookup whose read failed does not save, so it never
  waits for a Redis that is down twice. Once connected, the client reconnects by itself, waiting at
  most 2 seconds between tries, and commands fail at once while it is disconnected.
- Reads decide whether Redis counts as available. The server logs once when Redis cannot be read
  and once when it can again. A failed save is logged at debug level only. While Redis is down,
  Lettuce also logs its reconnect attempts, at most once every 5 seconds.
- The server starts even when Redis is down, since it connects on first use.
- Redis has no password for now. The platform limits access in the cluster, so that only the
  altinn-pdp pods can reach it. This matters, since anyone who can write to Redis can turn a `DENY`
  into a `PERMIT`. A password can be added later in `DECISION_CACHE_REDIS_URL`, without a code
  change.
- Redis evicts entries when it is full, for example with `maxmemory-policy allkeys-lru` as in
  `compose.yaml`, instead of refusing saves.

**The accepted stale-`PERMIT` window is still the time to live, plus a small margin.** The age of a
cached decision is counted on the pod that reads it, from the moment the writing pod asked Altinn.
If the reader's clock is behind the writer's, the decision looks younger than it is, and may be
served for up to that clock difference past the time to live. The entry's expiry in Redis, which
runs from when it was saved, bounds this: a decision is never served for longer past the time to
live than the call to Altinn took.

### Positive Consequences

- With several pods, a question is billed once for all of them, and a `no-cache` refresh reaches
  every pod.
- The cache survives restarts and deploys of the server.
- Operators choose between availability (`BYPASS`) and cost control (`REJECT`) when Redis is down.
  Either way, a slow or unreachable Redis costs a call at most a few times the timeout, a few
  hundred milliseconds by default, and usually far less, since a Redis that is down refuses at once.
- Nothing changes for existing deployments: the in-memory cache is still the default, and Redis is
  only needed by operators who choose it.

### Negative Consequences

- Operators who choose Redis have another service to run, secure and monitor, and a secret to
  manage. Changing the key secret empties the cache.
- With `BYPASS`, a Redis outage means every lookup is billed, as if the cache were off. With
  `REJECT`, it means callers get `503`, and a Redis that restarts now and then is seen by callers.
  After a restart, the pods fill the empty cache with billed lookups.
- While only one pod can reach Redis, the two pods may answer the same question differently, one
  from the cache and one from Altinn. Both stay within the caller's `max-age`.
- Concurrent misses each make their own call to Altinn, unlike in the in-memory cache, where misses
  on one pod share one.
- Two saves of the same question race, and the last one wins, even when it holds the older answer.
  A `no-cache` refresh can then be undone, for at most the time to live.
- A cache hit costs a round-trip to Redis, which is slower than reading the pod's own memory.
- The stale-`PERMIT` window can exceed the time to live by the clock difference between pods, as
  described above.
- Two cache implementations must be kept in step with the same rules.

If the bill or measurements ask for it, these can be added later: shared calls for concurrent misses,
a save that keeps the newer answer, a short in-memory cache in front of Redis, and a circuit breaker.

## Pros and Cons of the Options

### Redis as an optional cache-aside cache, chosen with `DECISION_CACHE_TYPE`, next to the in-memory cache

- Good, because the pods share one hit rate, `no-cache` refreshes every pod, and the cache survives
  deploys.
- Good, because Redis only sees an HMAC of the question, so a leaked Redis does not reveal who
  asked about what without the key secret.
- Good, because cache-aside heals itself after an outage, with nothing to replay.
- Good, because existing deployments keep the in-memory cache and need nothing new.
- Bad, because it is one more thing that can be down between a caller and an answer, though the
  timeout and the failure mode keep its effect bounded.
- Bad, because both implementations have to be maintained.

### Redis replacing the in-memory cache

- Good, because there is only one cache implementation to maintain.
- Bad, because every deployment that wants a cache would need Redis, including those with a single
  pod, where the in-memory cache is simpler, faster and just as effective.
- Bad, because it changes what an upgrade needs for operators who already use the in-memory cache.

### Keep only the in-memory cache, and run fewer pods or route the same question to the same pod

- Good, because there is nothing new to run.
- Bad, because fewer pods trades availability for hit rate.
- Bad, because routing by question needs the router to read the request body, and a `no-cache`
  refresh still only reaches the pod it is routed to while pods come and go.
- Bad, because the cache is still lost on every deploy.

## References

- Partially supersedes [Cache PDP decisions in memory per pod, with freshness chosen by the caller](2026-10-19-cache-pdp-decisions-in-memory-with-caller-chosen-freshness.md)
- Follows up [Offer Altinn PDP lookups as a shared REST API](2026-10-14-rest-api-for-pdp-lookup.md)
