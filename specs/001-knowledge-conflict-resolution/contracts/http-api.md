# Contract — HTTP API (`/api`)

> **Tier:** authoritative (specs/)
> **Purpose:** Specifies the HTTP endpoint surface — existing and new routes for sync, ingest, and conflict review.
> **Audience:** Implementers and clients of the `/api` endpoint.
> **Lifecycle:** durable

Extends the existing `MemoryEndpoint` (`api/MemoryEndpoint.java`). Existing routes
(`/remember`, `/recall`, `/stats`, `/recent`, `/compare`, `/forget`) are preserved. New routes
add sync, ingest reporting, and conflict review. All under `@HttpEndpoint("/api")`.

## Existing (unchanged surface)

| Method | Path | Body → Response |
|---|---|---|
| POST | `/remember` | `{text}` → `{graph, commit}` — now also writes provenance envelope |
| POST | `/recall` | `{question, strategy}` → `{answer, context[], strategy}` |
| GET | `/stats` | → `{chunks, entities, commits}` |
| POST | `/compare` | `{question}` → `{question, results[]}` |
| POST | `/forget` | → `{status}` — now honors `on_source_removal` semantics |

## New — sync & ingest (Workstreams B, D)

### POST `/sync`
Trigger a vault/corpus directory sync.
- **Request**: `{ paths?: string[] }` (omit = full sync of `vault/` + `corpus/`)
- **Response**: `IngestReport`
```json
{
  "total": 17, "new": 4, "corroborating": 11, "conflicting": 2,
  "suppressed": 0, "resurrections": 0,
  "facts": [
    { "subject": "ex:tyler", "predicate": "ex:currentEmployer", "object": "ex:globex",
      "layer": "derived", "source": "corpus/notes/2026-07-01.md", "outcome": "conflicting",
      "disagreesWith": "ex:acme", "resolution": "flagged", "served": "ex:acme" }
  ]
}
```
- **Errors**: `409 overlap` (a path in both layers), `422 vault-leak` (corpus file ≈ vault file),
  `409 exact-file-dup` (content hash already ingested).

**Invariant (FR-D2)**: a `conflicting` fact is *always* persisted + flagged, **never** blocked.
Under Model 1 a cross-layer `conflicting` fact is RESOLVED (authored `served`) and surfaced on
`/disagreements`; only a genuine no-winner becomes CONTESTED. Only `corroborating` (duplicate) and
file-level dups/leaks are blocked.

## New — value lookup (Phase H — the fact store's primary read)

### GET `/fact?subject=<s>&predicate=<p>`
The served (Model-1) value for a subject+predicate, with provenance — or the contested/unknown
state. This is the fact store's primary structured read (there is no NL Q&A `/recall`).
- **Response** `FactResponse` — one of:
```json
{ "subject": "tyler", "predicate": "currentEmployer", "state": "RESOLVED",
  "served": "acme", "servedLayer": "authored", "resolvedBy": "layer-precedence",
  "source": "vault/x.md", "flaggedCount": 1 }
```
```json
{ "subject": "tyler", "predicate": "homeCity", "state": "CONTESTED", "served": null,
  "candidates": [ {"object":"portland","layer":"authored","source":"vault/a.md"},
                  {"object":"seattle","layer":"authored","source":"vault/b.md"} ],
  "reason": "authored-tie" }
```
```json
{ "subject": "unknown", "predicate": "x", "state": "UNKNOWN", "served": null }
```
- Never throws on missing data — `UNKNOWN` is a valid answer.

## New — conflict review (Workstream C)

**Two surfaces, two states (Model 1).** A cross-layer disagreement is RESOLVED by
layer-precedence (authored served) and appears on `/disagreements` as a review/staleness signal.
Only a genuine no-winner (authored tie / same-layer, no discriminator) is CONTESTED and appears
on `/conflicts`.

### GET `/disagreements`  (RESOLVED + flagged — cross-layer)
Facts where a lower-precedence source disagrees with the served value. The read is unambiguous;
this is a staleness/review signal, not a blocked read.
- **Response**: `{ disagreements: [...] }`
```json
{ "subject": "ex:tyler", "predicate": "ex:currentEmployer",
  "served": "ex:acme", "servedLayer": "authored", "resolvedBy": "layer-precedence",
  "flagged": [ {"object": "ex:globex", "layer": "derived", "source": "corpus/notes/…", "conf": 0.7} ] }
```

### GET `/conflicts`  (CONTESTED — cascade produced no winner)
Facts the cascade could not resolve (e.g. two authored values, no source-priority discriminator).
- **Response**: `{ conflicts: ContestedState[] }` where each is
```json
{ "subject": "ex:tyler", "predicate": "ex:homeCity",
  "candidates": [
    {"object": "ex:portland", "layer": "authored", "source": "vault/people/tyler.md", "conf": 1.0},
    {"object": "ex:seattle",  "layer": "authored", "source": "vault/imports/hr.md",   "conf": 1.0}
  ],
  "reason": "authored-tie", "read": "freeze-incumbent", "incumbent": "ex:portland" }
```

### POST `/conflicts/resolve`
Human resolves a CONTESTED fact.
- **Request**: `{ subject, predicate, winner: <object IRI>, note? }`
- **Response**: `{ status: "resolved", winner }`
- Writing the winner is an **authored** edit (goes through the vault, X1); the endpoint records
  the decision and clears `contested`.

## Read-time semantics (FR-C4a, FR-C7)

- **Cross-layer** (a value exists at a higher-precedence layer): the read returns the
  higher-layer value (layer-precedence resolves it). Never contested; surfaced on
  `/disagreements`.
- **CONTESTED** (cascade produced no winner) on `/recall`, `/stats`: returns per policy
  `contested_read` — `freeze-incumbent` if a prior value existed, else `both-contested` (or
  `unknown` under strict policies).

Never silently flip; never silently blank.
