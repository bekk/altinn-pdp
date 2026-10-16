# No access control in the application itself; rely on the platform's OPA policies

Status: accepted

## Context

`altinn-pdp-rest-server` runs on SKIP, Kartverket's internal platform. Any caller who can reach the
service's URL can call its API. The application itself does not authenticate callers, does not
check who is calling, and does not limit which `resourceId` a caller may ask about. That is a
deliberate choice, not an oversight, and this ADR records why.

SKIP already offers Open Policy Agent (OPA) as shared platform functionality: a sidecar that sits in
front of a workload, evaluates a Rego policy against the request, and only lets matching requests
through. At Kartverket, each workload that wants to use `altinn-pdp-rest-server` gets its own OPA
policy and data document, configured outside of this repository, that limits that workload to a
specific, pre-agreed list of `resourceId`s it may ask the PDP about. The application never sees a
request that the platform's policy would have rejected.

## Decision

Do not build authentication or authorization into `altinn-pdp-rest-server` or
`altinn-pdp-client`. Leave access control entirely to whatever platform the service is deployed on.
At Kartverket, that means OPA in front of the service, configured with per-workload data limiting
each caller to its own list of `resourceId`s.

## Consequences

* The application has no notion of "who is calling" or "which workload is this". It trusts the
  network path to it entirely. It must only ever be deployed behind something that enforces access
  control, such as SKIP's OPA sidecar; it is not safe to expose directly.
* Kartverket's specific choice, an OPA sidecar with a per-workload `resourceId` allowlist, lives
  entirely outside this repository, in platform configuration. Changing which workload may ask
  about which resource never requires a code change or a release here.
* Because no access-control logic, secrets, or assumptions about a particular platform are baked
  into the code, the repository can stay public, and the client library and REST server can be
  reused as-is by other organisations with a different access control model, or none at all. They
  provide their own equivalent of the OPA layer.
* This also means the application cannot protect itself if deployed carelessly. Anyone adopting
  this project is responsible for putting an equivalent access control layer in front of it
  themselves.

## References

* [Open Policy Agent](https://www.openpolicyagent.org/) — the policy engine SKIP offers as
  platform functionality, used here to restrict each workload to its own `resourceId`s.
* [README.md](../../README.md) — current modules and deployment of this project.
