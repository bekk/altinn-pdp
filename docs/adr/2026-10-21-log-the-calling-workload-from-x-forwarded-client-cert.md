# Log the calling workload from X-Forwarded-Client-Cert

## Status

Proposed

## Context

To find the calls a given app made, or to see which app sent a call, the logs need to name the
caller. The application does not authenticate callers, as recorded in
[the ADR on access control](2026-10-15-no-application-level-access-control.md), so it has no caller
identity of its own to log.

The proxy in front of the service that terminates the caller's mTLS connection can pass the caller's
identity on in the `X-Forwarded-Client-Cert` header, as a SPIFFE ID such as
`spiffe://cluster.local/ns/team/sa/app`. A proxy that appends its own entry puts it after any
entries the caller sent, so only the last one comes from the proxy.

## Decision

Put the SPIFFE ID from the last entry in `X-Forwarded-Client-Cert` into the MDC as `workload` on
every line logged during a call. Use it for logs only, never to decide what a caller may do. If the
header is missing, or its last entry has no SPIFFE ID, the field is left out.

## Consequences

* The 2026-10-15 ADR still stands: the application makes no access-control decisions. It no longer
  holds that the application has no notion of which workload is calling, but that notion is only
  used in logs.
* `workload` can only be trusted where a proxy in front of the application sets or appends the
  header itself. In any other deployment, the caller chooses the value, so it must never be used
  for anything but finding log lines.
* Deployments without the header work as before, just without `workload`. Nothing in the server
  requires a particular proxy or platform.
