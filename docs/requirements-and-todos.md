# Requirements & TODOs — Handoff

> **Tier:** exploratory (docs/)
> **Purpose:** Cross-machine handoff notes — decision history, open questions, and what-to-do next.
> **Audience:** Anyone resuming the work on another machine.
> **Lifecycle:** churns

**Status:** Working notes for cross-machine handoff. Design directions below are **not
committed to implementation** unless a task is checked off.
**Last updated:** 2026-07-05
**Companion doc:** `docs/policy-and-conflict-design.md` (the full design rationale — read it
first for the *why*; this doc is the *what-to-do*).

> **Handoff note:** Project memory lives outside this repo (`~/.claude/projects/...`) and
> does **not** travel with git. This doc + `policy-and-conflict-design.md` are the source of
> truth on the other machine. Everything needed to resume is here. The decision history
> (§H) and mirrored memory notes (Appendix) exist so the *reasoning trail* survives the move,
> not just the conclusions.

> **⚠ Superseded (authority moved to `specs/`).** Since these notes were written, the work was
> formalized into the authoritative `specs/001-knowledge-conflict-resolution/` set, and the
> conflict model evolved to **Model 1**: cross-layer disagreement is **RESOLVED** (authored
> served) + **flagged**, *not* `contested`. Where this doc's notes (C8/C9) or the Appendix imply
> a cross-layer fact is `contested`, that is **not** Model 1 — defer to `spec.md` FR-C4a + `glossary.md`.
> `contested`/`freeze-incumbent`/`both-contested` apply only to a genuine no-winner. This file is
> now the exploratory/decision-history record, not the source of truth.

---

## H. Decision history & provenance — how we arrived here

This architecture was not designed top-down; it emerged from a chain of pressure-tests, and
several of its load-bearing rules exist **because the user pushed back on an earlier version.**
Recording that trail so any later reader can reconstruct *why* a rule is the way it is.

### Session 1 — 2026-07-04 (prior)
- Explored evolving `semantic-memory` toward a **hybrid Vault-LD + Cognee** model: files as
  source of truth (Vault-LD) + LLM extraction from prose (Cognee). Reference: Tony Seale's
  Vault-LD spec.
- Sketched a **policy dial** with 10 orthogonal axes; identified the essential subset
  (precedence, cardinality, deletion).
- **Decision → format:** user rejected nested YAML ("too many dimensions") in favor of
  **markdown with indented `key = value` directives** — prose explains *why*, directives are
  the parseable *what*. (This is a durable user preference, mirrored in the Appendix.)
- Left explicitly as "keep for the future," not a commitment.

### Session 2 — 2026-07-05 (this session)
Chronological trail of how the rules firmed up. **Bold = a pivot the user steered.**

1. **Resumed after a reboot** from memory; recapped the direction and the open thread:
   conflict resolution was still unsettled.
2. **Four real-project examples** (personal assistant, compliance/legal, CRM/enrichment, eng
   runbook) were generated to test the format. *Finding:* `on_conflict` is **never a single
   scalar** — every realistic policy qualifies it by cardinality, layer, or confidence.
   → seeded the idea that conflict is keyed, not flat.
3. **Vendor comparison** (Databricks / Snowflake / Elastic, then GraphRAG vendors). *Finding:*
   they all solve *retrieval* + *lineage* but treat conflict as an upstream, imperative
   (`MERGE` / last-write-wins) problem. A **declarative fact-level conflict layer is white
   space** — that became the project's reason to exist.
4. **Triple = unit of knowledge** established as the reason conflict can be first-class:
   a triple *is* a fact, so provenance and conflict rules have something to attach to.
5. **Pressure-test: authored-vs-authored conflict** (precedence can't break the tie). *Finding:*
   `tiebreak = most-recent` silently discards a trusted human fact — a bug, not a resolution.
   → produced the **resolution cascade** (layer-aware, lexicographic) and the **authored-tie
   rule: `flag`, never most-recent** (C4–C6). Contested-read semantics (C8) fell out here.
6. **Pressure-test: deletion.** I framed "a derived fact contradicting a *deleted* authored
   fact" as the hard case. **→ USER CORRECTION:** *"if a fact was deleted from the file, it's
   no longer a fact — there's no conflict."* Conceded. This **reframed the entire deletion
   axis**: the real hard problems are **resurrection via re-extraction, derivation-lineage
   cascade, and retraction-vs-negation** (Workstream E) — *not* contradiction with deleted
   facts.
7. **→ USER INSIGHT:** *"enforce a hard separation between authored facts and the documents
   used to derive facts."* Adopted as an **invariant** (Workstream B): `vault/` vs `corpus/`,
   one file one role. My refinement: **separation of *sources* ≠ isolation of *facts*** —
   derived facts still reconcile against authored ones; cross-layer conflict is the *product*.
8. **→ USER DIRECTION:** *"block users from uploading content already in authored facts."*
   Refined into the **three-outcome ingest gate** (D1): the block applies to the **redundant/
   corroborating** case only — **never** to contradictions (blocking those would defeat the
   whole design). Surfaced entity/predicate resolution as the hard dependency (D6).
9. **→ USER QUESTION on scalability** (is directory-watching the bottleneck?). Corrected:
   the ceiling is **LLM extraction cost + triple-store substrate + reconciliation tax**, not
   file-watching. Most of the gap vs the warehouses is *by design* (different job).
10. **Traced all 5 model call sites** (§0); established embeddings are a **direct hand-rolled
    Gemini HTTP call** bypassing the Akka SDK.
11. **→ USER DIRECTION:** *"open-source models embedded in the JVM for dev, Gemini key only for
    prod."* Became **Workstream A** (in-JVM ONNX embeddings + local LLM, key-gated).

**The through-line:** the design converged on one sentence — *suppress facts that agree with
the vault; surface facts that disagree with it; never block a contradiction* — and that
sentence is the product of the user's corrections at steps 6, 7, and 8, not the initial draft.

---

## 0. Where things are (current-code reference map)

**LLM / model call sites** (5 total — 3 generative, 1 embedding service):

| Component | Type | File | Pipeline |
|---|---|---|---|
| `GraphExtractorAgent.extract` | generative (structured) | `src/main/java/com/example/application/GraphExtractorAgent.java` | write / extract |
| `QaAgent.answer` | generative (free text) | `.../QaAgent.java` | read / synthesize |
| `SearchTypeClassifierAgent.classify` | generative (structured) | `.../SearchTypeClassifierAgent.java` | read / route (FEELING_LUCKY only) |
| `GeminiEmbeddings.embed` | embedding (direct HTTP) | `.../GeminiEmbeddings.java` | write **and** read |

- Write path: `RememberWorkflow.java` (durable) + inline `MemoryEndpoint.remember` (`api/MemoryEndpoint.java:47`).
- Read path: `application/retrievers/*` — `Rag/Graph/Hybrid` call `QaAgent`; `Chunks` embeds only; `Lexical` = BM25, **zero** model calls.
- `/compare` (`MemoryEndpoint.java:77`) fans out 5 strategies → ~8–9 model calls per request.
- The three Agents resolve their model via **Akka's `ModelProvider`**; embeddings **bypass** the SDK with hand-rolled `java.net.http`.

---

## Workstream A — Local / embedded dev models (no API key required)

**Goal:** A new user runs the project with **zero external accounts**. Local open-source
models run for embeddings + generation in dev; the user supplies `GOOGLE_AI_GEMINI_API_KEY`
only when moving to production data.

**Requirements**
- [ ] **A1.** Selection is gated on the Gemini key: **absent → local models; present → Gemini.** No code edit to switch.
- [ ] **A2.** Dev mode makes **no outbound network calls at inference time** (weights may download once at setup — see A7).
- [ ] **A3.** Prod behavior is unchanged when the key is present.

**Embeddings (in-JVM via LangChain4j ONNX)**
- [ ] **A4.** Introduce an `Embeddings` interface with two impls: `LocalOnnxEmbeddings`, `GeminiEmbeddings` (refactor existing static class behind it).
- [ ] **A5.** Pick the local model deliberately re: dimensions. **Default to a 768-dim model (`all-mpnet-base-v2`) to keep the Fluree vector schema unchanged.** If choosing 384-dim (`all-MiniLM-L6-v2`, faster/smaller), the vector schema + stored vectors become dev-specific.
- [ ] **A6.** Document the invariant: **vectors are not comparable across models.** Dev and prod stores are **not interchangeable**; switching backends requires **re-embedding the corpus**. Enforce/warn if a store's embedding-model tag ≠ the active model.

**LLM (local)**
- [ ] **A7.** Choose the local LLM path and record the decision:
  - **Ollama** (separate local process, OpenAI-compatible) — easier, faster, drops cleanly into Akka's `ModelProvider`. *Recommended default.*
  - **Jlama** (pure-Java, in-JVM; needs JDK 21 + `--add-modules jdk.incubator.vector`) — literal "embedded in JVM," more integration work against the Agent wiring.
- [ ] **A8.** Configure the local provider for the three Agents via Akka `ModelProvider` — **do not bypass the SDK.** Verify exact provider config keys against `akka-context/sdk/model-provider-details.html.md` before wiring.
- [ ] **A9.** First-run UX: document/automate the one-time weight download (hundreds of MB–GB). Make it explicit, not a silent hang.

**Known risk to design around**
- [ ] **A10.** **Structured output is the weak link.** `GraphExtractorAgent` and `SearchTypeClassifierAgent` use `responseConformsTo(...)` (strict JSON schema); small local models (1–3B) are worst exactly here. Add retry/repair or a smaller-schema fallback for dev, and set expectations that dev-mode graph quality is lower. `QaAgent` (free text) is unaffected.

**Acceptance:** fresh checkout, no key set → `/remember` and `/recall` work end-to-end with
zero network at inference; setting the key switches to Gemini with no code change.

---

## Workstream B — Hard source separation (Vault-LD)

**Invariant:** a document is either **curated** (`vault/`) or **mined** (`corpus/`), never
both; extraction never persists a fact back into the curated set. (Detail: design doc §3.)

- [ ] **B1.** Directory model: `vault/**` = authored layer, `corpus/**` = derived layer.
- [ ] **B2.** Layer = a **function of source location**, assigned at sync/ingest, immutable per assertion.
- [ ] **B3.** `on_overlap = error` — a path claimed by both layers fails the sync.
- [ ] **B4.** `authored.extraction = assist-only` — a `vault/` file may *propose* triples; they persist only once promoted to frontmatter. Nothing derived persists *from* a vault file.
- [ ] **B5.** **Vault-leak guard** — reject a `corpus/` upload that is substantially a copy of a `vault/` file.
- [ ] **B6.** Preserve the counter-principle: separation of *sources* ≠ isolation of *facts*. Derived facts still land in the same graph and are reconciled against authored facts (cross-layer conflict is the product, not a bug).

---

## Workstream C — Conflict-resolution engine

(Detail: design doc §2, §4, §5.)

- [ ] **C1.** Add a **provenance envelope** to every stored triple: `{ layer, source, asserted, conf }`.
- [ ] **C2.** Cardinality model: `functional` vs `multi_valued` predicates; `multi_valued` never conflicts.
- [ ] **C3.** Conflict detection: same subject + predicate on a `functional` predicate.
- [ ] **C4.** **Resolution cascade** (lexicographic, first strict winner wins): `layer-precedence → source-priority → confidence → recency → terminal action`.
- [ ] **C5.** Cascade is **layer-aware**: recency may break *derived* ties but **never authored** ties.
- [ ] **C6.** **Authored-tie rule**: no discriminator → terminal action `flag`, **never `most-recent`**. Refuse loudly.
- [ ] **C7.** `source_priority` ordering within a layer (design open Q: total order vs equal-priority buckets — see §10).
- [ ] **C8.** Contested-read semantics: `freeze-incumbent` when a prior value existed, else `both-contested`. Never silently flip or blank.
- [ ] **C9.** Contested-state storage: mark both triples `contested`, record `contestedWith` + reason.

---

## Workstream D — Ingest gate (dedup, don't blind)

(Detail: design doc §7.)

- [ ] **D1.** On each `corpus/`-derived triple, classify vs authored layer into **three** outcomes:
  - **new** (S/P not authored) → persist as `derived`
  - **corroborating** (exact S-P-O match) → do **not** persist a duplicate; attach as evidence
  - **conflicting** (same S-P, different O) → **flag; never block**
- [ ] **D2.** Hard rule: *"block content already in authored facts"* = the **corroborating** case only. Never block a **contradiction** — that is the one thing the design must never do.
- [ ] **D3.** Prefer **record-as-corroboration** over discard (keep a confidence/staleness trail, e.g. "N docs corroborate X").
- [ ] **D4.** File-level hard blocks: `on_exact_file_dup = reject` (content hash); vault-leak guard (shared with B5).
- [ ] **D5.** Ingest report/summary: "N facts: X new, Y corroborating, Z conflicting."
- [ ] **D6.** **Entity + predicate resolution** (hard dependency — gate quality = resolution quality). Handle aliasing (`Acme`/`Acme Corp`) and predicate synonymy (`employer`/`currentEmployer`/`worksAt`). Policy axis: `resolution = strict | alias-aware | embedding-assisted`.

---

## Workstream E — Deletion semantics

(Detail: design doc §6.)

- [ ] **E1.** A deleted/tombstoned fact **stops asserting** and cannot conflict (whether `cascade`-removed or `freeze`/`archive`-tombstoned).
- [ ] **E2.** `on_source_removal` policy: `cascade | orphan | freeze | archive` (per-layer overridable).
- [ ] **E3.** **Resurrection via re-extraction**: deleting an authored fact does not stop `corpus/` from re-deriving it. Govern via `re_extraction = overwrite | append-versioned | diff-add-only | require-review`. Frame the re-derivation as a *feature* (surface "N docs still assert this").
- [ ] **E4.** **Derivation-lineage cascade**: when a fact used to *infer* downstream derived facts is removed, decide whether downstream facts are retracted or merely weakened (open Q — §10).
- [ ] **E5.** **Retraction vs negation**: a deleted line cannot express negation. **Negations are first-class authored facts** in `vault/` ("Tyler does *not* work at Acme"); the corpus never negates.

---

## Workstream F — `policy.md` format & parser

(Detail: design doc §8 has a full annotated example.)

- [ ] **F1.** Format: **markdown with indented `key = value` directives** — prose = *why*, directives = *what*. (Not nested YAML; not pure INI.)
- [ ] **F2.** Parser: extract directive blocks under `##` sections; ignore prose; support `#` inline comments and `*` wildcard defaults.
- [ ] **F3.** Sections to support: `Sources`, `Precedence`, `Cardinality`, `Resolution`, `Conflict Resolution`, `Ingest Gate`, `Resolution Quality`, `Deletion`, `Provenance`.
- [ ] **F4.** Ship 4 preset policies as examples/tests: Personal assistant, Compliance/legal, CRM/enrichment, Eng runbook (the one-line-change personality table, design doc §8).
- [ ] **F5.** Validation: reject unknown keys, contradictory directives, and `on_overlap` source collisions at load time.

---

## Cross-cutting requirements

- [ ] **X1.** Two ingest paths (prose via `/api/remember`, vault directory sync) land in **one** Fluree store with per-triple provenance in named graphs (`authored:` vs `derived:`).
- [ ] **X2.** Author-time **lint** to catch authored-vs-authored conflicts *before* sync (pre-commit or pre-sync — placement is open, §10). Advantage over the warehouse model: the contradiction shows up as a git-diffable file conflict in review.
- [ ] **X3.** Keep the write path's crash-safety (Akka `RememberWorkflow`); the inline endpoint path and read retrievers currently have no durability around external model calls — decide whether that's acceptable once models can be local.

---

## Consolidated open questions (decide on the other machine)

1. Derivation-lineage cascade depth — retract vs. weaken downstream derived facts (E4).
2. Contested-read default for a single fresh sync introducing two authored values — `both-contested` vs `unknown` (C8).
3. Author-time lint placement — pre-commit hook vs pre-sync (X2).
4. Resolution-quality tiers — exact `strict | alias-aware | embedding-assisted` behavior + cost/latency (D6).
5. `source_priority` — total order vs explicit equal-priority buckets that always `flag` (C7).
6. Local LLM path — Ollama (recommended) vs Jlama (in-JVM ideal) (A7).
7. Local embedding dimension — 768 (`all-mpnet-base-v2`, schema-preserving) vs 384 (`all-MiniLM`, faster) (A5).

---

## Suggested sequencing

1. **Workstream A** first — it's self-contained, unblocks new-user onboarding, and touches only the model call sites (no schema/knowledge-model changes). Highest value, lowest risk.
2. **B + F** next — source separation + policy parser establish the substrate the rest needs.
3. **C + D + E** — the reconciliation engine, once provenance envelopes (C1) exist.

---

## Appendix — session memory notes (mirrored from `~/.claude` memory)

These live in per-machine memory that does **not** travel with git. Mirrored here verbatim in
substance so the reasoning survives the move. On the other machine, treat these as the
authoritative record of intent.

### Memory: project — Vault-LD direction
> Evolving `semantic-memory` toward a hybrid combining Cognee-style prose ingestion (LLM
> extraction via `GraphExtractorAgent`) with Vault-LD authored knowledge (markdown +
> YAML-LD frontmatter as source of truth; graph is a projection). Ref: Tony Seale's Vault-LD.
>
> **Why:** Vault-LD (files = source of truth, RDF = projection, git-diffable, no LLM in the
> identity/schema path) is compatible with this project's Fluree provenance. Cognee
> (zero-authoring extraction) is compatible with the existing extractor. They are
> complementary at the data-structure level. The white space both miss — and so do
> Databricks/Snowflake/Elastic and even GraphRAG vendors — is a **declarative, fact-level
> conflict-resolution layer.**
>
> Decisions: hard source separation (`vault/` vs `corpus/`); separation of sources ≠
> isolation of facts; layer-aware resolution cascade; authored-tie → `flag`; three-outcome
> ingest gate (never block contradictions); deletion = stops asserting (hard problems are
> resurrection/lineage/negation); negations are first-class authored facts. Local/embedded
> dev models gated on `GOOGLE_AI_GEMINI_API_KEY`. Still a design direction, not committed.

### Memory: feedback — policy file format preference
> For user-editable config (policies, rule catalogs, tuning knobs), prefer **markdown with
> embedded `key = value` directives** over deeply nested YAML.
>
> **Why:** In the 2026-07-04 conversation the user rejected a 10-axis YAML policy as "too many
> dimensions" and asked for something more readable. The winning format: markdown where prose
> sections explain the *why* and indented `key = value` lines carry the machine-parseable
> rule. Doubles as documentation, greppable, no indentation gotchas, readable top-to-bottom.
>
> **How to apply:** default to markdown-with-directives for any user-facing config surface.
> Reserve YAML for machine-only configs. If a human might ever read the file, prose +
> `key = value` beats nested YAML.
