# Answer errors as Problem Details, with a name as `type`

Status: accepted  
Deciders: Team Tilgangsstyring

## Context and Problem Statement

The server answered errors in a JSON format of its own: `error`, a text for people, `code`, one of
`VALIDATION_ERROR`, `MALFORMED_BODY`, `INVALID_CACHE_CONTROL`, `UPSTREAM_REJECTED`, `UPSTREAM_ERROR`
and `INTERNAL_ERROR`, and with `VALIDATION_ERROR` an `errors` list of every field that failed.
maskinporten-management, from the same team, had another format of its own, with other codes. A
wrong path or method got Ktor's own answer, not ours.

Problem Details (RFC 9457) is the standard format for errors from HTTP APIs, sent as
`application/problem+json`. Should our errors follow it, and if so, what should say which kind of
error it is?

## Decision Drivers

- A caller should handle errors from this API and from maskinporten-management with the same code,
  in a format that tools and libraries already know.
- Code must be able to tell errors apart by a value that does not change.
- A caller should still see every invalid field in one answer.
- Nothing to host, and nothing that changes when a repo or a file moves.
- No one uses the API yet, so changing the format is cheap now.

## Considered Options

- Keep our own format
- Problem Details, with `about:blank` as `type` and the kind of error in a `code` field
- Problem Details, with a name for each kind of error as `type`
- Problem Details, with a link to a page that explains the error as `type`

## Decision Outcome

Chosen option: "Problem Details, with a name for each kind of error as `type`", because that is how
RFC 9457 means `type` to be used, and it needs nothing to host. The same kind of error has the same
name in this API and in maskinporten-management.

Errors are sent as `application/problem+json`:

```json
{
  "type": "tag:kartverket.no,2026:validation-error",
  "title": "Validation failed",
  "status": 400,
  "errors": [
    { "field": "resourceId", "code": "MISSING", "message": "resourceId is required" }
  ]
}
```

- `type` says what went wrong, and code should check it. It is a `tag:` URI (RFC 4151):
  `tag:kartverket.no,2026:` followed by a name. It is not a link. `2026` is the year the names were
  made, and it stays the same in later years.
- `title` is the same for every error of a type.
- `status` is the HTTP status.
- `detail` explains this error, and is left out when the title says it all.
- `errors` comes only with `validation-error`. It lists every field that failed, each with `field`,
  `code` and `message`, as before.

| `type`, after `tag:kartverket.no,2026:` | Status | When |
|---|---|---|
| `validation-error` | 400 | One or more fields are missing or not valid |
| `malformed-request` | 400 | The body is not valid JSON, or a field has the wrong type |
| `invalid-cache-control` | 400 | `max-age` in `Cache-Control` is not a whole number of seconds, 0 or more |
| `upstream-rejected` | 400 | The values passed this API's checks, but Altinn rejected them |
| `not-found` | 404 | There is no endpoint at the path |
| `method-not-allowed` | 405 | The endpoint does not accept the method |
| `upstream-error` | 502 | This API could not get an answer from Altinn or Maskinporten |
| `internal-error` | 500 | An unexpected error in this API |

### Positive Consequences

- Callers can handle errors from this API and from maskinporten-management the same way, and tools
  that know RFC 9457 can read them.
- A wrong path or method gets an answer in the same format as every other error.

### Negative Consequences

- Callers that read `error` and `code` must change. `MALFORMED_BODY` is now `malformed-request`, the
  name maskinporten-management uses.
- The names are part of the API. Renaming one breaks callers, so a new meaning needs a new name. A
  new kind of error that both APIs can have should get the same name in both.
- `type` is not a link, so what each type means is described in the OpenAPI spec.

## Pros and Cons of the Options

### Keep our own format

- Good, because nothing changes for callers.
- Bad, because callers must learn a format that only this API uses, and maskinporten-management had
  another one.
- Bad, because tools that know Problem Details cannot read it.

### Problem Details, with `about:blank` as `type` and the kind of error in a `code` field

- Good, because it follows the format, and callers can keep checking `code`.
- Bad, because `about:blank` means "nothing more than the status code", so tools see every error
  with the same status as the same error.
- Bad, because `title` must then be the status text, like "Bad Request".

### Problem Details, with a name for each kind of error as `type`

- Good, because `type` says what went wrong, as RFC 9457 intends.
- Good, because there is nothing to host, and the same error has the same name in both APIs.
- Bad, because the name is not a link, so a person must look in the OpenAPI spec to see what it
  means.

### Problem Details, with a link to a page that explains the error as `type`

- Good, because RFC 9457 encourages a link to a page that explains the error.
- Bad, because the link depends on where the page is. If the repo or the file moves, `type` changes,
  and that breaks callers.
- Bad, because an error that both APIs have gets two different types, unless both link to the same
  page.

## References

- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [RFC 4151: The 'tag' URI Scheme](https://www.rfc-editor.org/rfc/rfc4151)
- [The same decision in maskinporten-management](https://github.com/kartverket/maskinporten-management/blob/main/docs/adr/2026-10-22-answer-errors-as-problem-details.md)
