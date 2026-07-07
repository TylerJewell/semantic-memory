# Phase 1 — Data Model

> **Tier:** authoritative (specs/)
> **Purpose:** Defines the entities, relationships, and provenance envelope underpinning the feature.
> **Audience:** Implementers building the storage and reconciliation layers.
> **Lifecycle:** durable

**Feature**: Knowledge Conflict Resolution | **Date**: 2026-07-06
**Source**: spec.md Key Entities + `docs/policy-and-conflict-design.md` §2–§8.

## Entity relationships (overview)

```text
Policy ──drives──> IngestGate ──classifies──> Triple(derived)
   │                   │                          │
   │                   └──emits──> IngestReport   │ shares subject+predicate with
   ▼                                              ▼
ResolutionCascade ──produces──> ContestedState <──flags── Triple(authored)
   ▲                                              ▲
Predicate(cardinality) ──gates conflict on────────┘
Every Triple ──carries──> ProvenanceEnvelope ──layer from──> SourceLayerResolver(vault/|corpus/)
```

## Triple

The unit of knowledge and of conflict. Currently written implicitly by `FlureeClient.remember`
(subject `@id`, predicate, object `@id`) **without provenance**. This feature makes the triple
explicit and provenance-bearing.

| Field | Type | Notes |
|---|---|---|
| `subject` | IRI | `ex:` slug of entity name (existing convention) |
| `predicate` | IRI | `ex:` slug of relationship label |
| `object` | IRI \| literal | entity IRI or literal value |
| `envelope` | ProvenanceEnvelope | **new** — attached to every assertion |
| `active` | boolean | false = tombstoned; a tombstone does not assert (FR-E1) |
| `derivedUsing` | IRI[] \| null | support edges for lineage (R4); null for base facts |

Stored in Fluree under a **named graph** selected by layer (`authored:` / `derived:`), with
`asserted`/`conf`/`source` as reified metadata on the assertion.

**Validation**
- A functional predicate MUST have ≤1 *active* object per subject in a given layer after
  resolution (else `contested`).
- `object` entity IRIs must resolve to a known entity (existing extractor invariant).

## ProvenanceEnvelope

| Field | Type | Notes |
|---|---|---|
| `layer` | enum `authored \| derived` | function of source location; immutable per assertion (FR-B1) |
| `source` | string (file path) | originating `vault/…` or `corpus/…` file |
| `asserted` | timestamp | when the assertion entered the store |
| `conf` | double `0..1` | `1.0` for authored; extractor confidence for derived |

## Layer

Derived, not stored independently — a function of `source`:
- `source` under `vault/**` → `authored`
- `source` under `corpus/**` → `derived`
- a path matched by both globs → **sync error** (`on_overlap = error`, FR-B2)

## Predicate

| Field | Type | Notes |
|---|---|---|
| `iri` | IRI | the predicate |
| `cardinality` | enum `functional \| multi_valued` | from policy `Cardinality`; default `multi_valued` |

`multi_valued` predicates never conflict — multiple objects coexist (FR-C2). Only `functional`
predicates enter conflict detection.

## Disagreement  (RESOLVED + flagged — Model 1, cross-layer)

Produced when the cascade **does** pick a winner but a lower-precedence source disagrees. The
read is unambiguous (the winner is served); this is a review/staleness signal, not a blocked read.

| Field | Type | Notes |
|---|---|---|
| `subject` / `predicate` | IRI | the fact key |
| `served` | Triple | the cascade winner (e.g. the authored value) |
| `resolvedBy` | enum | which rung decided (`layer-precedence \| source-priority \| confidence \| recency`) |
| `flagged` | Triple[] | the losing lower-precedence assertion(s), kept + surfaced |

Surfaced on `GET /api/disagreements`. Never becomes `contested`; `freeze-incumbent` does not apply.

## ContestedState  (CONTESTED — cascade produced NO winner)

Produced **only** when the cascade cannot pick a single winner for a functional subject+predicate
(an authored tie, or same-layer with no discriminator). Cross-layer disagreements are NOT
contested — layer-precedence resolves them (see Disagreement above).

| Field | Type | Notes |
|---|---|---|
| `subject` / `predicate` | IRI | the contested fact key |
| `candidates` | Triple[] | ≥2 competing active triples the cascade could not rank |
| `contestedWith` | IRI[] | cross-links between candidates |
| `reason` | enum | `authored-tie \| same-layer-no-discriminator \| fresh-both` |
| `incumbent` | Triple \| null | pre-conflict value if one existed |
| `read` | enum `freeze-incumbent \| both-contested \| unknown` | resolved per policy `contested_read` (R3) |

**State transitions**: `resolved(single winner)` → not contested; `contested` → `resolved` when
an author edits/deletes to break the tie, or a higher-precedence source arrives. Never
auto-flips by recency for authored facts (FR-C5/C6).

## Negation

A first-class **authored** fact (FR-E4) asserting a negative, e.g. `NOT(ex:tyler ex:worksAt
ex:acme)`. Stored in the `authored:` graph. During ingest, a derived triple matching a negation's
subject+predicate+object is actively **suppressed** (not persisted, reported as suppressed). The
corpus never produces negations.

## Policy (parsed model)

Parsed from `policy.md` (contract in `contracts/policy-md.md`). Sections → typed config:

| Section | Keys | Type |
|---|---|---|
| Sources | `authored`, `corpus`, `on_overlap`, `authored.extraction` | globs + enums |
| Precedence | `precedence`, `source_priority` | ordered layers; bucketed source list (R7) |
| Cardinality | `functional`, `multi_valued` | predicate sets (`*` default) |
| Resolution | `resolve_by.authored`, `resolve_by.derived` | ordered discriminator lists (layer-aware) |
| Conflict Resolution | `on_conflict`, `scope`, `contested_read` | enums |
| Ingest Gate | `on_exact_file_dup`, `on_vault_leak`, `on_authored_match`, `on_authored_conflict`, `report` | enums |
| Resolution Quality | `resolution` | `strict \| alias-aware \| embedding-assisted` (R6) |
| Deletion | `on_source_removal`, `re_extraction`, `negation` | enums |
| Provenance | `retention` | enum |

**Load-time validation** (FR-F2): reject unknown keys, contradictory directives, and
`on_overlap` source-glob collisions.

## IngestReport

Emitted per sync (FR-D4): `{ total, new, corroborating, conflicting, suppressed, resurrections }`
plus a per-fact list with its outcome. Corroborating facts attach an evidence record
("N docs corroborate S-P-O") rather than a duplicate triple (FR-D3).

## Delta vs current code

| Today (`domain/KnowledgeGraph.java`, `FlureeClient`) | After |
|---|---|
| Entities + Relationships, no metadata | + per-triple ProvenanceEnvelope |
| Single ledger, one implicit layer | Named graphs `authored:` / `derived:` |
| No cardinality notion | `Predicate.cardinality` from policy |
| `forgetAll` wipes everything | `DeletionPolicy`: cascade/orphan/freeze/archive + tombstones |
| No conflict concept | ContestedState + ResolutionCascade |
| Embed via static `GeminiEmbeddings` | `Embeddings` SPI (local ONNX \| Gemini) + model-id tag |
