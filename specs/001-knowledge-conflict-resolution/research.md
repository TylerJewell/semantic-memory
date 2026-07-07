# Phase 0 — Research & Decisions

> **Tier:** authoritative (specs/)
> **Purpose:** Records the resolved open questions and the decisions/rationale that remove ambiguity from design.
> **Audience:** Implementers and reviewers tracing why each choice was made.
> **Lifecycle:** durable

**Feature**: Knowledge Conflict Resolution | **Date**: 2026-07-06
**Purpose**: Resolve the 7 consolidated open questions from `docs/requirements-and-todos.md`
into decisions so Phase 1 design has no `NEEDS CLARIFICATION` left. Each entry: Decision /
Rationale / Alternatives considered.

---

## R1 — Local LLM path *(open Q #6, FR-A4)* — REVISED 2026-07-06

**Decision**: **Jlama** (pure-Java, in-JVM) as the dev LLM, wired through Akka's `ModelProvider`.
Runs the three Agents' inference *inside the JVM* — no separate daemon. Requires JDK 21 +
`--add-modules jdk.incubator.vector`.

**Rationale**: Matches the standing directive — *models embedded **in the JVM** for dev*
(`requirements-and-todos §H`, item 11). Decisive advantage for **EC-001**: with in-process
inference there is **no inference-time socket at all** — not even a `localhost` daemon call — so
"zero external network in local mode" becomes a **structural** fact ("no `HttpClient` to an
external host is constructed") rather than a runtime network-capture judgment. This tightens
EC-001 and is a candidate to downgrade it from `human-signoff` toward `auto`.

**Alternatives considered**: *Ollama* (separate local daemon, OpenAI-compatible on
`localhost:11434`) — easier to wire and a broader model catalog, but it is a **separate process**,
which contradicts the "embedded in the JVM" directive and leaves a loopback inference socket that
muddies the zero-network claim. Rejected. (An earlier revision of this decision defaulted to
Ollama for lower integration effort; that silently traded away the embedded-in-JVM requirement and
is corrected here.) The `ModelSelector` seam still allows an Ollama profile later if ever wanted.

**Follow-on**: EC-001's autonomy should be re-evaluated (`human-signoff` → possibly `auto`) once
the "no external `HttpClient` in local mode" structural check exists.

---

## R2 — Local embedding dimension / model *(open Q #7, FR-A3/A5)*

**Decision**: **768-dim `all-mpnet-base-v2`** via LangChain4j in-JVM ONNX, matching the current
Gemini 768-dim output.

**Rationale**: Keeps the Fluree vector schema and any stored vectors' dimensionality unchanged,
so the store shape is identical between dev and prod. Avoids a dev-specific schema fork.

**Alternatives considered**: 384-dim `all-MiniLM-L6-v2` (smaller, faster) rejected as default —
it changes the vector schema and makes dev/prod stores structurally different. Note it as an
opt-in "fast dev" profile behind the model-identity tag. **Invariant recorded**: vectors are not
comparable across models; dev and prod stores are not interchangeable; switching backends
requires re-embedding the corpus (enforced by the model-identity tag, FR-A5).

---

## R3 — Contested-read default for a single fresh sync introducing two authored values *(open Q #2, FR-C7)*

**Decision**: **`both-contested`** — return both values tagged `contested` when no prior
incumbent existed; `freeze-incumbent` only when a pre-conflict value existed.

**Rationale**: Never silently flip, never silently blank (a hard invariant). With no incumbent
to freeze, returning both preserves the most information and makes the contradiction visible to
downstream consumers, matching the "surface, don't hide" principle. Configurable via
`contested_read`.

**Alternatives considered**: `unknown` (return nothing, tagged) — safer for consumers that
cannot handle two values, but discards information and can read as "no data" rather than
"disputed." Keep `unknown` as a policy option for strict/compliance profiles.

---

## R4 — Deletion impact: what happens to dependent facts *(open Q #1, FR-E4)* — REVISED 2026-07-06 (user)

**Decision**: **Neither auto-weaken nor auto-retract — scan and ask.** Deleting a fact F triggers a
**deletion-impact scan** that surfaces every fact that should now be *questioned*, and the user
adjudicates each {keep-valid | alter | remove}. The impact set has three categories:
- **lineage dependents** — derived facts inferred *using* F (via `derivedUsing` support edges);
- **flagged rivals** — facts F was outranking in a conflict, which may now be valid once F is gone;
- **re-assertions** — corpus documents that still assert F (the resurrection case).
The scan is **read-only**; nothing changes until the user decides. Encoded as EC-026.

**Rationale**: The earlier v1 stance (auto-weaken) silently changed data the user might still trust,
and auto-retract risks cascading deletions on fragile inference paths. Both take the decision away
from the human. A deliberate delete is a high-signal moment where the human *should* be asked what
the downstream consequences are — this matches the project's north-star (surface, don't silently
resolve) and the human-dignity principle applied to deletions, not just conflicts.

**Requires**: a lineage index (`derived → support facts`) — tracked separately from the `Triple`
record to stay additive. Flagged-rivals need no lineage (derivable from the store by shared
subject+predicate). Populating the lineage index from the real extraction/inference pipeline is a
follow-on; the scanner accepts the index as input.

**Alternatives considered**: auto-weaken (prior v1 decision) — rejected, silently mutates trusted
data. Auto-retract — rejected, cascading and takes the human out of the loop. No scan — rejected,
leaves stale dependents with false confidence.

---

## R5 — Author-time lint placement *(open Q #3, FR-X2)*

**Decision**: **Pre-sync** lint as the enforced gate (runs inside `VaultSync` before any write),
**plus** an optional **pre-commit hook** wrapper that shells the same check for fast local
feedback.

**Rationale**: Pre-sync is the correct enforcement point — it cannot be bypassed by committing
directly, and it is where layer assignment already happens. A pre-commit hook is a convenience
mirror (same code path) so authored-vs-authored conflicts show up as a git-diffable review
signal, which is the design's stated advantage over the warehouse model. One implementation, two
entry points.

**Alternatives considered**: Pre-commit only — bypassable and machine-specific. Pre-sync only —
loses the git-review ergonomics the design values.

---

## R6 — Resolution-quality tiers *(open Q #4, FR-D5)*

**Decision**: Three explicit tiers on the `resolution` axis, defaulting to **`alias-aware`**:
- `strict` — exact string match on subject/predicate/object. Cheapest, zero extra model calls.
- `alias-aware` — normalized + curated alias/synonym maps (entity aliasing, predicate synonymy).
  No embedding calls. **Default.**
- `embedding-assisted` — fall back to cosine similarity over entity/predicate embeddings above a
  threshold for near-duplicate detection. Highest recall, adds embedding cost/latency per gate.

**Rationale**: Gate quality = resolution quality (a hard dependency). `alias-aware` catches the
common `Acme`/`Acme Corp` and `employer`/`currentEmployer` cases without per-fact model calls,
keeping ingest cheap. `embedding-assisted` is reserved for messy corpora where the cost is
justified. `strict` exists for deterministic tests and compliance.

**Alternatives considered**: Single hardcoded matcher — rejected; the design explicitly makes
this a policy axis because the cost/recall trade-off is corpus-dependent.

---

## R7 — `source_priority` ordering *(open Q #5, FR-C7)*

**Decision**: **Total order with explicit equal-priority buckets.** `source_priority` is a list
where consecutive entries are strictly ranked; entries grouped (same bucket) are equal and, when
they collide, fall through to the next cascade discriminator or the terminal `flag`.

**Rationale**: A strict total order is too rigid (forces arbitrary ranking between genuinely
peer sources); pure equality loses the common case where `vault/people/` really does outrank
`vault/imports/`. Buckets express both. When a bucket can't break a tie, the cascade continues
exactly as designed — and an authored bucket tie terminates in `flag`, honoring FR-C6.

**Alternatives considered**: Strict total order only (design doc's simpler option) — rejected as
too rigid. Timestamp fallback within a bucket — rejected for authored facts (violates the
authored-tie rule).

---

## Cross-cutting confirmations (no open question, recorded for design)

- **Provenance storage in Fluree** — use **named graphs** `authored:` and `derived:` plus reified
  per-triple metadata (`asserted`, `conf`, `source`) rather than a second store. Confirmed
  feasible against the current `FlureeClient` insert/query shape.
- **Durability (X3)** — keep `RememberWorkflow` as the crash-safe write path. Decision: once
  models can be local, the inline `MemoryEndpoint.remember` path remains a convenience (snappy UI)
  but the **sync + ingest-gate path routes through the workflow** so reconciliation writes are
  durable. Read retrievers stay non-durable (idempotent reads).
- **Structured output on small local models (A6)** — add a repair loop (re-prompt with the schema
  violation) and a reduced-schema fallback for dev; set expectation that dev-mode graph quality is
  lower. `QaAgent` (free text) unaffected.

**Result**: All 7 open questions resolved. No `NEEDS CLARIFICATION` remains for Phase 1.
