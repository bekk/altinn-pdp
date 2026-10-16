# Drop minimumAuthenticationLevelOrg from the REST API response

## Status

Accepted

## Context

A `PERMIT` from Altinn's PDP can carry obligations attached as XACML attribute assignments. Two of
them carry a minimum authentication level: `urn:altinn:minimum-authenticationlevel` and
`urn:altinn:minimum-authenticationlevel-org`. The client library models both, since obligations are
exposed unfiltered there.

The `-org` variant is Altinn's own fallback for a **service owner** acting under
`urn:altinn:org`, not for a system user or a person. The subjects this API asks the PDP about are
always a system user or a person, acting on behalf of a `customerOrganizationNumber`; they are
never an Altinn service owner. Altinn therefore never has a reason to attach
`minimum-authenticationlevel-org` to a decision this API returns, and if it ever did, no caller of
this REST API would know what to do with it: it answers a question about a different kind of
subject than the one they asked about.

## Decision

`altinn-pdp-rest-server`'s `AuthorizeResponse` no longer includes
`minimumAuthenticationLevelOrg`. Only `minimumAuthenticationLevel` is returned. The client library
is unaffected: `PdpAuthorization.minimumAuthenticationLevelOrg` and the raw `obligations` list
still expose it, for callers who embed the client directly and want every obligation Altinn sends.

## Consequences

* REST API callers only ever see one authentication-level field, matching the only kind of subject
  this API supports, instead of a second field that can never apply to them and would only invite
  the question of what to do with it.
* `openapi.json`, the OpenAPI examples, and the README's REST API documentation were updated to
  drop the field; it is a genuine removal from the contract, not an optional one.
* A future resource that lets a service owner be the subject, if ever added, would need to
  reconsider whether `minimumAuthenticationLevelOrg` becomes relevant again.

## References

* Implemented in commit `004d872`, "Stop returning minimumAuthenticationLevelOrg".
