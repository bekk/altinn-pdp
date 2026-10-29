# Ask about sub-resources with custom attributes, as the policy editor's Type and Id rows

Status: proposed
Deciders: Team Tilgangsstyring

## Context and Problem Statement

A feature request asks for PDP lookups of a sub-resource that has an action of its own, first for
the `kv-geonorge` resource. Altinn Studio's policy editor supports this. Under «Hvilken
sub-ressurser skal regelen gjelde for?», a rule lists sub-resources. Each one starts with a fixed
`urn:altinn:resource` row holding the resource id, and the author adds more rows of Type and Id
with «Legg til en innsnevring av sub-ressursen», for example `urn:kv:geonorge` and `dataset`.

The editor saves each row as a resource attribute in the rule: Type as `AttributeId`, Id as
`AttributeValue`, compared with `string-equal`. All rows of one sub-resource go in the same
`AllOf`, so the rule only matches a request that has every row. Until now, the client only sent
`urn:altinn:resource` and the customer's organisation number, so no such rule could match.

Altinn Authorization does not document attributes like these. Dialogporten uses them against the
same PDP: `urn:altinn:subresource`, and any attribute given as `<type>:<id>`, split at the last
colon.

How should a caller tell this API which sub-resource it asks about?

## Decision Drivers

- A caller should be able to copy a sub-resource from the policy editor without translating it.
- A caller must not be able to replace the resource or the customer this API already sends.
- Input that no policy made in the editor can match should be refused with `400`, not passed on
  and answered `NOT_APPLICABLE`, which looks like a real "no access".
- Existing callers must not notice the change.
- The client is used by other teams, so its public API should stay small.

## Considered Options

- A list of `{ type, id }`, named after the editor's columns
- A list of `{ attributeId, value }`, named after XACML
- One string per row, such as `urn:kv:geonorge:dataset`, split at the last colon as Dialogporten
  does
- A `subresource` field of its own for `urn:altinn:subresource`
- No change: register each sub-resource as a resource of its own in the Resource Registry

## Decision Outcome

Chosen option: "A list of `{ type, id }`, named after the editor's columns", because each entry is
one row the policy's author can see, it allows the custom types the editor allows, and type and id
never need to be split.

- The REST request gets an optional `resourceAttributes` field, and `PdpClient.authorize` an optional
  `resourceAttributes: List<ResourceAttribute>`. They are sent in the resource category, after
  `urn:altinn:resource` and the customer. Without them, the request to Altinn is the same as before.
- `urn:altinn:resource` stays a field of its own, `resourceId`, and is still required. Every
  request needs it, since it names the resource whose policy Altinn uses, and every sub-resource in
  the editor starts with that row. It cannot be sent as a resource attribute.
- `urn:altinn:subresource` gets no field of its own. A caller sends it as a resource attribute, like
  any other row: `{ "type": "urn:altinn:subresource", "id": "metadata" }`.
- `type` must be `urn:` followed by one or two names separated by `:`, such as `urn:kv:geonorge`.
  A name has only lowercase letters, `-` and `_`. The editor adds `urn:` when the author leaves it
  out, and shows it after saving, so this API does not add it.
- A `type` under `urn:altinn:` is refused, except `urn:altinn:subresource`. This API
  sends `urn:altinn:resource` and the customer itself, and the rest of the namespace is Altinn's.
- `id` must not be blank or contain `:`. The editor splits Type and Id at the last colon, so no
  policy made there has a colon in an id.
- Each `type` may only be sent once. XACML would treat two values as a bag, and a rule naming
  either of them would match. A repeated type is a `CONFLICTING` error on every entry that has it.
- The checks follow [#28](https://github.com/bekk/altinn-pdp/pull/28): `ResourceAttribute.parse`
  and `authorize` refuse bad input with `ValidationException`, and the server checks the same
  rules first, so the caller gets a `400` with `resourceAttributes[<index>].type` or `.id` in
  `errors`.
- `resourceAttributes` is part of the decision cache's key, so a decision for one sub-resource is
  never served for another.

### Positive Consequences

- A caller sends the rows it sees in the editor, with the same names.
- Existing callers are not affected, since the field is optional.
- A caller cannot send a second `urn:altinn:resource` or customer.
- Rows that no policy made in the editor can have are refused before Altinn is asked.

### Negative Consequences

- A rule whose sub-resource only has the `urn:altinn:resource` row matches whatever resource
  attributes are sent. A sub-resource only gets narrower access than the resource through an action
  of its own. This API cannot enforce that, so the OpenAPI spec and the README warn about it.
- Support rests on how the editor saves policies, on Dialogporten using the same attributes, and on
  Altinn's PDP being generic XACML, not on documentation from Altinn. One `PERMIT` and one
  `NOT_APPLICABLE` against TT02 should confirm it before callers rely on it.
- Some policies written by hand in XML cannot be asked about: an id with a colon, or a type under
  `urn:altinn:` other than `urn:altinn:subresource`. Loosening a check later does not break
  callers, while tightening one would.
- The rules are written twice, in the client and in the server. A rule missing from the server
  makes the client throw, which the caller gets as a `500`. A test sends every input the client
  refuses through the API to catch that.
- A request asks about one sub-resource. Asking about several takes one call each.

## Pros and Cons of the Options

### A list of `{ type, id }`, named after the editor's columns

- Good, because the names are the ones the policy's author sees, Type and Id.
- Good, because type and id arrive apart, so no splitting rules are part of the contract.
- Good, because it covers both `urn:altinn:subresource` and custom types such as `urn:kv:geonorge`,
  and sub-resources with more than one row.
- Bad, because someone reading the XACML policy sees `AttributeId` and `AttributeValue`, not Type
  and Id.

### A list of `{ attributeId, value }`, named after XACML

- Good, because the names match the XACML policy and the request Altinn gets.
- Good, because it is as flexible as `{ type, id }`.
- Bad, because a caller has to know that the editor's Type is `attributeId` and its Id is `value`.

### One string per row, split at the last colon as Dialogporten does

- Good, because it is compact, and the same format Dialogporten and the editor use internally.
- Bad, because an id with a colon silently becomes part of the type, so the splitting rule becomes
  part of the contract.
- Bad, because the caller has to join Type and Id from the editor itself.

### A `subresource` field of its own for `urn:altinn:subresource`

- Good, because the attribute is the one Dialogporten uses, so a field named after it is
  recognisable.
- Good, because on its own it would be the smallest change: one optional string.
- Bad, because on its own it cannot express custom types such as `urn:kv:geonorge`, which the first
  requesting resource uses, or a sub-resource with more than one row.
- Bad, because next to `resourceAttributes` it would be a second way to send the same attribute,
  since `{ "type": "urn:altinn:subresource", "id": "metadata" }` already does it.

### No change: register each sub-resource as a resource of its own

- Good, because nothing changes in this API, and each resource has its own policy and delegation.
- Bad, because every sub-resource has to be registered, owned and maintained on its own, even when
  it shares most of its rules with the resource.
- Bad, because the editor already offers sub-resources, so policies with them will exist whether
  this API supports them or not.

## Addendum

A sub-resource that needs access entirely separate from its resource is still better off as a
resource of its own. Resource attributes are for narrowing a resource, not for replacing one.

## References

- Follows [Offer Altinn PDP lookups as a shared REST API](2026-10-14-rest-api-for-pdp-lookup.md)
- Errors as in [Answer errors as Problem Details, with a name as `type`](2026-10-22-answer-errors-as-problem-details.md)
- Validation split from [#28](https://github.com/bekk/altinn-pdp/pull/28)
- Altinn Studio's policy editor, joining Type and Id:
  [`policy-editor/src/utils/index.ts`](https://github.com/Altinn/altinn-studio/blob/40ce0e0ac4ae4c6955de84a389932c4cac4a604a/src/Designer/frontend/packages/policy-editor/src/utils/index.ts)
- Altinn Studio, splitting them into XACML:
  [`PolicyConverter.cs`](https://github.com/Altinn/altinn-studio/blob/40ce0e0ac4ae4c6955de84a389932c4cac4a604a/src/Designer/backend/PolicyAdmin/PolicyConverter.cs)
- Dialogporten's sub-resource attributes:
  [`DecisionRequestHelper.cs`](https://github.com/Altinn/dialogporten/blob/main/src/Digdir.Domain.Dialogporten.Infrastructure/Altinn/Authorization/DecisionRequestHelper.cs)
