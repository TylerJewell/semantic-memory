# Implementation Plan: Knowledge Conflict Resolution (Vault-LD + Cognee hybrid)

> **Tier:** authoritative (specs/)
> **Purpose:** Maps the spec to a phased delivery strategy — architecture, workstreams, and sequencing.
> **Audience:** Implementers executing the build.
> **Lifecycle:** churns

**Branch**: `001-knowledge-conflict-resolution` | **Date**: 2026-07-06 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-knowledge-conflict-resolution/spec.md`
**Design sources**: `docs/policy-and-conflict-design.md`, `docs/requirements-and-todos.md`

## Summary

Evolve `semantic-memory` from a flat, provenance-free RAG+graph store into a hybrid Vault-LD +
Cognee knowledge system whose defining feature is a **declarative, fact-level conflict-
resolution layer**. Two thrusts: (1) a **local-model dev mode** so the project runs with no
external accounts, gated on `GOOGLE_AI_GEMINI_API_KEY`; (2) a **provenance + reconciliation
engine** — every triple carries `{layer, source, asserted, conf}`, sources are hard-separated
into `vault/` (authored) and `corpus/` (derived), and a layer-aware cascade decides conflicts,
flagging (never silently resolving) contradictions and authored ties. Behavior is driven by a
human-readable `policy.md`.

The work lands in three delivery phases (below), sequenced lowest-risk-first per the design's
own recommendation: **A** (models) → **B + F** (substrate: separation + policy) → **C + D + E**
(the reconciliation engine).

## Technical Context

**Language/Version**: Java 21 (Akka Java SDK parent `3.6.0`)
**Primary Dependencies**: Akka Java SDK 3.6.0 (Agent, Workflow, HttpEndpoint, ModelProvider,
LangChain4j structured output); Jackson; `java.net.http`. **New**: LangChain4j ONNX embeddings
(`langchain4j-embeddings-all-mpnet-base-v2`, in-JVM), an in-JVM local LLM via Jlama
(pure-Java, JDK 21 + `jdk.incubator.vector` — see research R1).
**Storage**: Local **Fluree** HTTP server (`127.0.0.1:8090`, ledger `memory:main`) — one
substrate for triples + vectors + BM25. Provenance to be stored via named graphs / reified
metadata. Fluree is external to Akka's own entity/view persistence (accessed via `FlureeClient`).
**Testing**: JUnit (Akka SDK TestKit for Agents/Workflow/Endpoint); policy-parser unit tests;
ingest-gate + conflict-cascade table tests; a local-model integration smoke.
**Target Platform**: Local JVM (dev, no network at inference); Akka platform (prod, Gemini).
**Project Type**: Single Akka service (backend) with a static UI endpoint. No new module split.
**Performance Goals**: Interactive `/remember` and `/recall` (seconds, LLM-bound). Dev-mode
first-run tolerates one-time weight download (hundreds of MB–GB). The ceiling is LLM extraction
cost + triple-store substrate + reconciliation tax — **not** file-watching.
**Constraints**: Dev mode = zero inference-time network; vectors not cross-model comparable
(store tagged with embedding-model identity); structured output degrades on small local models.
**Scale/Scope**: Single-user / small-team second-brain scale. `vault/` small and curated;
`corpus/` large and messy. One Fluree ledger.

**Current-code reference map** (grounding, from design doc §0):

| Concern | Where | Note |
|---|---|---|
| Graph extraction (structured) | `application/GraphExtractorAgent.java` | `responseConformsTo(KnowledgeGraph.class)` |
| QA synthesis (free text) | `application/QaAgent.java` | via `ModelProvider` |
| Search routing (structured) | `application/SearchTypeClassifierAgent.java` | FEELING_LUCKY only |
| Embeddings (direct HTTP) | `application/GeminiEmbeddings.java` | bypasses SDK; 768-dim `gemini-embedding-001` |
| Durable write | `application/RememberWorkflow.java` | extract → embed+persist, crash-safe |
| Inline write + read fan-out | `api/MemoryEndpoint.java` | `/remember`, `/recall`, `/compare`, `/forget` |
| Unified store client | `application/FlureeClient.java` | insert / cosine / neighborhood / BM25 / stats; **no provenance today** |
| Extraction contract | `domain/KnowledgeGraph.java` | entities + relationships; **no layer/source/conf** |

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status: UNAVAILABLE — not blocking.** The Akka SDK constitution is not initialized in this
repo (`.akka/constitution/akka-sdk-constitution.md` absent; `akka specify init .` never run). No
project constitution file exists to gate against. Proceeding under the Akka SDK's implicit
component conventions plus the design's own invariants, which act as this feature's gates:

1. **Layer is a function of source location**, assigned at sync, immutable per assertion.
2. **A document is either curated or mined, never both**; extraction never persists back into
   the curated set.
3. **Suppress agreement, surface disagreement; never block a contradiction.**
4. **At an authored tie, refuse loudly (`flag`) — never auto-pick by recency.**
5. **A deleted/tombstoned fact stops asserting and cannot conflict.**
6. **Negations are first-class authored facts.**
7. **Model access goes through Akka `ModelProvider`** (embeddings are the one sanctioned
   exception, itself refactored behind an `Embeddings` interface).

> Recommend running `akka specify init .` to download the real constitution before
> `/akka:tasks`, so the task breakdown can re-check formally.

## Project Structure

### Documentation (this feature)

```text
specs/001-knowledge-conflict-resolution/
├── plan.md              # This file
├── research.md          # Phase 0 — resolves the 7 consolidated open questions
├── data-model.md        # Phase 1 — triple + provenance + layers + contested state + policy model
├── quickstart.md        # Phase 1 — run local; sync vault/corpus; see a conflict surfaced
├── contracts/
│   ├── policy-md.md      # policy.md directive grammar (the config contract)
│   ├── http-api.md       # /api ingest, sync, conflict-review, report endpoints
│   └── embeddings-spi.md # Embeddings interface + model-identity tag
└── tasks.md             # Phase 2 output (/akka:tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/com/example/
├── api/
│   ├── MemoryEndpoint.java        # extend: ingest report, conflict-review, sync trigger
│   └── UiEndpoint.java            # (unchanged; UI may later show contested facts)
├── application/
│   ├── GraphExtractorAgent.java   # A6: structured-output repair/fallback for local models
│   ├── QaAgent.java               # A4: model via ModelProvider (local|gemini)
│   ├── SearchTypeClassifierAgent.java
│   ├── RememberWorkflow.java      # X3: keep crash-safety; write provenance envelope
│   ├── FlureeClient.java          # C1/X1: named graphs authored:/derived:, provenance, tombstones
│   ├── embeddings/                # NEW (Workstream A)
│   │   ├── Embeddings.java        #   interface (embed + modelId)
│   │   ├── GeminiEmbeddings.java  #   moved behind interface
│   │   └── LocalOnnxEmbeddings.java
│   ├── model/                     # NEW — ModelProvider selection (key-gated local|gemini)
│   │   └── ModelSelector.java
│   ├── sync/                      # NEW (Workstream B) — vault/corpus directory sync
│   │   ├── SourceLayerResolver.java   # path -> layer; overlap + vault-leak guards
│   │   └── VaultSync.java
│   ├── policy/                    # NEW (Workstream F)
│   │   ├── Policy.java            #   parsed model
│   │   └── PolicyParser.java      #   markdown + key=value directives
│   ├── conflict/                  # NEW (Workstream C)
│   │   ├── ProvenanceEnvelope.java
│   │   ├── ResolutionCascade.java #   layer -> source-priority -> confidence -> recency -> terminal
│   │   └── ContestedState.java
│   ├── ingest/                    # NEW (Workstream D)
│   │   ├── IngestGate.java        #   new | corroborating | conflicting
│   │   ├── EntityResolver.java    #   strict | alias-aware | embedding-assisted
│   │   └── IngestReport.java
│   ├── deletion/                  # NEW (Workstream E)
│   │   └── DeletionPolicy.java    #   cascade|orphan|freeze|archive; re-extraction; negation
│   └── retrievers/                # (unchanged; may become provenance-aware later)
└── domain/
    ├── KnowledgeGraph.java        # extend: per-triple layer/source/conf on write
    ├── Triple.java                # NEW — subject/predicate/object + envelope
    └── Predicate.java             # NEW — cardinality (functional|multi_valued)

policy.md                          # NEW — active policy at repo root (+ 4 presets under specs contracts)
vault/                             # NEW — authored source dir
corpus/                            # NEW — derived source dir
src/test/java/com/example/...      # parser, cascade, gate, deletion table tests + local-model smoke
```

**Structure Decision**: Single Akka service, no module split. New logic is added as
plain `application/*` sub-packages (`embeddings`, `model`, `sync`, `policy`, `conflict`,
`ingest`, `deletion`) plus two new `domain` records. The Fluree substrate stays the single
store; provenance rides on named graphs and reified triple metadata rather than a new database.

## Implementation Phases (delivery sequencing)

> These are the *delivery* phases the user asked for. They are distinct from the SDD workflow's
> Phase 0/1/2 (research/design/tasks) above. `/akka:tasks` will expand each into ordered tasks.

### Phase A — Local / embedded dev models *(Workstream A; FR-A1..A6, X3)*
Self-contained, no schema change, only touches model call sites. **Do first.**
- `Embeddings` SPI + `LocalOnnxEmbeddings` (768-dim `all-mpnet-base-v2`, schema-preserving) +
  refactor `GeminiEmbeddings` behind it.
- `ModelSelector` gating on `GOOGLE_AI_GEMINI_API_KEY`; wire local LLM via Akka `ModelProvider`.
- Store embedding-model identity tag; warn on mismatch.
- Structured-output repair/fallback for `GraphExtractorAgent` + `SearchTypeClassifierAgent`.
- **Exit**: SC-001 (zero-network round-trip), SC (key present → Gemini unchanged).

### Phase B+F — Source separation + policy substrate *(Workstreams B, F; FR-B*, F*, X1, X2)*
Establishes provenance layers and the declarative config every reconciler reads.
- `vault/` + `corpus/` dirs; `SourceLayerResolver` (path→layer, overlap error, vault-leak guard).
- `VaultSync` writing triples with provenance into named graphs `authored:` / `derived:`.
- `Policy` + `PolicyParser`; 4 preset policies; load-time validation; author-time lint (X2).
- **Exit**: SC-002 (distinct-layer triples), SC-006 (presets load & change behavior).

### Phase C+D+E — Reconciliation engine *(Workstreams C, D, E; FR-C*, D*, E*)*
Depends on provenance envelopes from B.
- `ProvenanceEnvelope`, `Predicate` cardinality, conflict detection.
- `ResolutionCascade` (layer-aware; authored-tie → `flag`); `ContestedState` + contested reads.
- `IngestGate` (new/corroborating/conflicting) + `EntityResolver`; `IngestReport`; endpoint.
- `DeletionPolicy` (tombstones, re-extraction governance, first-class negation).
- **Exit**: SC-003 (0 silent overwrites), SC-004 (0 duplicates), SC-005 (100% authored-tie flag),
  SC-007 (no silent resurrection).

## Phase 0: Outline & Research

See [research.md](./research.md). Resolves the 7 consolidated open questions (local LLM path,
embedding dimension, contested-read default, derivation-lineage cascade, lint placement,
resolution-quality tiers, source-priority ordering) into decisions with rationale.

## Phase 1: Design & Contracts

See [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md).

## Complexity Tracking

No constitution violations to justify (constitution unavailable). One deliberate complexity
note: seven new `application/*` sub-packages. Justified — each maps 1:1 to an independent
workstream with distinct lifecycle and test surface; collapsing them would entangle the
reconciliation engine with model selection and sync, which the design explicitly separates.
