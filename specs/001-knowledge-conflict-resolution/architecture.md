# Architecture — Unified Provenanced Fact Store

> **Tier:** authoritative (specs/)
> **Purpose:** The target architecture after unification — one primitive, one API substrate, a pure fact store. Supersedes the RAG-demo surface.
> **Audience:** Implementers and reviewers; the refactor is driven by the Phase H ECs.
> **Lifecycle:** durable — changes only by deliberate edit + a spec-change receipt.

## Decision (2026-07-06)

The project is realigned from *two products sharing a database* (a Cognee-style RAG demo + a
conflict engine, on two different data models) into **one product: a provenanced fact store.**
The leverage is the **model**, not just the store. There is exactly **one first-class citizen —
the provenanced assertion** — and every capability is a typed operation over it.

**Read surface is structured only.** Natural-language Q&A (RAG) is dropped. You *reconcile and
inspect* facts; you do not *ask questions of* them. The only generative model use that remains is
**ingest-time extraction** (prose → facts). Embeddings, if used, serve **entity resolution**, not
semantic retrieval.

## The primitive

Everything is an `Assertion`. Nothing else is first-class — sources and embeddings hang off it.

```
╔═══════════════════════════════════════════════════════════╗
║  THE PRIMITIVE — the only first-class citizen              ║
║                                                           ║
║   Assertion {                                             ║
║     subject · predicate · object                          ║
║     ┌ layer   authored | derived ┐                        ║
║     │ source  vault/… | corpus/… | prose:<id>  │ envelope ║
║     │ asserted · conf · active    ┘                        ║
║     embedding ───────► entity-resolution assist (optional) ║
║   }                                                       ║
╚═══════════════════════════════════════════════════════════╝
```

## The substrate — four verbs over assertions

```
╭──────────────────────────────────────────────────────────────────────────╮
│  API SUBSTRATE — one surface, over provenanced assertions (fact store)      │
├───────────────┬───────────────────────┬──────────────────┬───────────────┤
│    INGEST      │    QUERY (structured) │      MUTATE       │    OBSERVE     │
│               │                       │                  │               │
│ POST /remember│ GET /fact             │ DELETE /fact     │ GET /stats    │
│   (prose →    │   (served value +     │  → impact scan   │ GET /recent   │
│    extract)   │    provenance)        │  → adjudicate    │               │
│ POST /sync    │ GET /disagreements    │ POST /conflicts/ │  (over        │
│   (dirs)      │ GET /conflicts        │      resolve     │   assertions) │
│               │                       │ POST /forget     │               │
╰───────┬───────┴──────────┬────────────┴─────────┬────────┴───────┬───────╯
        │                  │                       │                │
        ▼                  ▼                       ▼                ▼
╭──────────────────────────────────────────────────────────────────────────╮
│  APPLICATION — every verb is a function over the SAME primitive            │
│                                                                            │
│  ingest:            query:              mutate:            observe:        │
│   GraphExtractor     Reconciler          DeletionImpact     stats()        │
│   VaultSync          (served / Model 1)  (scan+adjudicate)                 │
│   SourceLayer        value lookup        Resolver                          │
│   IngestGate                             Negation/Tombstone                │
│        │                 │                    │                            │
│        └─────────────────┴──ResolutionCascade─┴──── EntityResolver ────────│
│                     (layer → source → confidence → recency)                │
╰──────────────────────────────────┬───────────────────────────────────────╯
                                    │  one shape in, one shape out
                                    ▼
              ╭──────────────────────────────────────────────╮
              │  FLUREE — single store                        │
              │   ex:Assertion nodes (+ optional vectors)     │
              │   immutable commits  =  provenance / audit    │
              ╰──────────────────────────────────────────────╯

   ┌─ MODEL SEAM (key-gated · no bypass, EC-006) — INGEST-TIME ONLY ─┐
   │   ModelSelector                                                 │
   │     no key →  Jlama (in-JVM) extraction   [+ LocalOnnx resolve] │
   │     key     →  Gemini extraction          [+ Gemini embed]      │
   └─────────────────────────────────────────────────────────────────┘
```

## Unification — two ingest doors, one graph

Both ingest paths produce the same primitive and flow into the same engine. This is what makes it
one product (FR-X1).

```
  prose  ──POST /remember──►  GraphExtractor ─┐
                                              ├─► Assertion (layer=derived)  ─┐
  vault/ ──┐                                  │                              │
  corpus/──┴─POST /sync────►  VaultSync ───────┴─► Assertion (auth | derived)─┤
                                                                             │
                              ┌──────────────────────────────────────────────┘
                              ▼
                         ResolutionCascade  (Model 1: layer-precedence decides)
                              │
              ┌───────────────┼───────────────────────────┐
              ▼               ▼                            ▼
        GET /fact        GET /disagreements           GET /conflicts
        served value     authored won, derived        authored tie, no winner
        (+ provenance)   flagged for review            → human resolves
```

## Decommissioned — the RAG demo (not the substrate)

The read side had a strategy bake-off that has no place in a fact store. All of it is removed:

```
  ✗ POST /recall (NL Q&A)     ✗ QaAgent                ✗ POST /compare
  ✗ ChunksRetriever          ✗ GraphCompletionRetriever ✗ HybridRetriever
  ✗ RagCompletionRetriever   ✗ LexicalChunksRetriever   ✗ FeelingLuckyRetriever
  ✗ SearchTypeClassifierAgent
     └─► the entire semantic-Q&A column collapses to structured reads:
         GET /fact + /disagreements + /conflicts
```

## Invariants this architecture adds (enforced by Phase H ECs)

1. **Single write model** — every ingested fact persists as a provenanced `ex:Assertion`; no code
   writes the legacy chunk/plain-triple model (EC-050).
2. **Unified ingest** — a fact added via `/remember` lands in the *same* graph as `/sync` facts and
   participates in reconciliation (EC-051).
3. **Structured reads only** — a value-lookup returns the served (Model-1) value; there is no NL
   Q&A endpoint and no read-side LLM (EC-052).
4. **Decommissioned** — no RAG-demo surface remains (EC-053).
5. **Observe over assertions** — counts/recency report over `ex:Assertion`, not `ex:Chunk` (EC-054).
