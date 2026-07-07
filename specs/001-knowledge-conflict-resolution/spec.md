# Feature Specification: Knowledge Conflict Resolution (Vault-LD + Cognee hybrid)

> **Tier:** authoritative (specs/)
> **Purpose:** Defines what the conflict-resolution feature must do — user scenarios, requirements, and success criteria.
> **Audience:** Implementers, reviewers, and stakeholders validating scope.
> **Lifecycle:** durable

**Feature Branch**: `001-knowledge-conflict-resolution`
**Created**: 2026-07-06
**Status**: Draft
**Input**: `docs/policy-and-conflict-design.md` + `docs/requirements-and-todos.md` — evolve
`semantic-memory` into a hybrid Vault-LD + Cognee knowledge system with a declarative,
fact-level conflict-resolution layer.

## Overview

Today `semantic-memory` ingests prose, extracts a knowledge graph with an LLM, and stores
triples + embeddings + BM25 in one Fluree ledger. Every fact is anonymous and equal: no
provenance, no source-of-truth, no notion of two facts disagreeing. This feature adds the
missing layer — **who asserted a fact, whether it is curated or mined, and what happens when
two sources disagree** — plus a local-model dev mode so a new user can run everything with no
external accounts.

The through-line of the whole design: **suppress facts that agree with the vault; surface
facts that disagree with it; never block a contradiction.**

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Run locally with zero external accounts (Priority: P1)

A new contributor clones the repo, sets no API keys, and runs `/remember` and `/recall`
end-to-end. Embeddings and generation run on in-process / local open-source models. Setting
`GOOGLE_AI_GEMINI_API_KEY` later switches to Gemini with no code change.

**Why this priority**: Self-contained; unblocks onboarding; touches only the model call
sites (no schema or knowledge-model change). Highest value, lowest risk — the design's
recommended first workstream.

**Independent Test**: Fresh checkout, no key set → POST `/api/remember` then `/api/recall`
return a real answer with zero outbound network calls at inference time. Set the key, restart,
and the same calls route to Gemini.

**Acceptance Scenarios**:

1. **Given** no `GOOGLE_AI_GEMINI_API_KEY`, **When** the service starts, **Then** it selects
   local embeddings + local LLM and logs which models are active.
2. **Given** local mode, **When** `/api/remember` is called, **Then** the chunk is embedded
   and a graph extracted with no external HTTP call.
3. **Given** the key is present, **When** the service starts, **Then** it selects Gemini and
   behavior is unchanged from today.

---

### User Story 2 - Curated vault vs mined corpus, kept apart (Priority: P2)

An author keeps deliberate facts in `vault/` (source of truth) and dumps raw material —
transcripts, PDFs, tickets — into `corpus/` (mined). A file is one or the other, never both.
Syncing assigns each triple a layer from its source directory and records provenance.

**Why this priority**: Establishes the substrate (provenance + layers) every later reconciler
needs. Pairs with the policy parser.

**Independent Test**: Put the same fact in a `vault/` file and a `corpus/` file; sync; query
the store and confirm two triples exist with `layer: authored` and `layer: derived`
respectively, each tagged with its source file. A file claimed by both layers fails the sync.

**Acceptance Scenarios**:

1. **Given** `vault/people/tyler.md` asserting a fact, **When** synced, **Then** the triple is
   stored with `{layer: authored, source: vault/people/tyler.md, conf: 1.0}`.
2. **Given** a path matched by both `authored` and `corpus` globs, **When** synced, **Then**
   the sync fails with an overlap error (`on_overlap = error`).
3. **Given** a `corpus/` upload that is substantially a copy of a `vault/` file, **When**
   ingested, **Then** it is rejected (vault-leak guard).

---

### User Story 3 - A contradiction is surfaced, never silently resolved (Priority: P2)

A `corpus/` document claims `Tyler currentEmployer Globex` while `vault/` asserts `Acme`. The
system does not blindly overwrite and does not hide the disagreement — layer-precedence resolves
the read in the vault's favor (`Acme` is served), and the corpus's `Globex` is recorded and
**flagged for review** as a disagreement (a staleness signal), never blocked. (Model 1:
cross-layer disagreement is RESOLVED + flagged, not CONTESTED — see plan/research.)

**Why this priority**: This is the product. The conflict engine + ingest gate are the reason
the feature exists.

**Independent Test**: Author `Acme` in vault, ingest a corpus doc asserting `Globex`, and
confirm (a) both triples persist, (b) a read returns the authored `Acme` (layer-precedence),
(c) the disagreement is surfaced for review, (d) the ingest report counts one disagreement —
and the read is never blocked or silently flipped to `Globex`.

**Acceptance Scenarios**:

1. **Given** an authored functional fact, **When** a corpus doc derives a *different* object
   for the same subject+predicate, **Then** the authored value is served (layer-precedence),
   the derived value is flagged for review, and both triples are kept — never blocked.
2. **Given** a corpus doc that *exactly matches* an authored fact, **When** ingested, **Then**
   no duplicate triple is stored; it is attached as corroborating evidence.
3. **Given** a corpus doc with a fact on a subject the vault never mentions, **When**
   ingested, **Then** it persists as a new `derived` fact.
4. **Given** two conflicting **authored** facts with no precedence discriminator, **When**
   reconciled, **Then** the terminal action is `flag` — never auto-pick by recency.

---

### User Story 4 - Delete, retract, and negate without resurrection surprises (Priority: P3)

An author removes a fact from a vault file. The fact stops asserting. If corpus documents
still claim it, re-extraction surfaces "N documents still assert this" rather than silently
resurrecting it. To positively state a negative ("Tyler does *not* work at Acme"), the author
writes a first-class negation in the vault.

**Why this priority**: Correctness of the lifecycle, but only meaningful once provenance and
the conflict engine exist.

**Independent Test**: Delete an authored fact still present in a corpus doc; re-sync; confirm
the fact is not silently restored as authored, and the re-extraction policy governs whether it
reappears as derived, is queued for review, or is suppressed by a negation.

**Acceptance Scenarios**:

1. **Given** a fact deleted from a vault file, **When** re-synced, **Then** the fact stops
   asserting and cannot conflict (removed or tombstoned).
2. **Given** a corpus doc still asserting a deleted authored fact, **When** re-extracted,
   **Then** the `re_extraction` policy decides the outcome and the report notes the
   resurrection attempt.
3. **Given** an authored negation `NOT(Tyler worksAt Acme)`, **When** a corpus doc derives
   `Acme`, **Then** the derived assertion is actively suppressed.

---

### User Story 5 - Change behavior by editing one policy file (Priority: P3)

An operator edits `policy.md` — a human-readable markdown file with embedded `key = value`
directives — to move the system between personalities (personal assistant, compliance KB,
CRM/enrichment, eng runbook) without touching the graph or the pipeline.

**Why this priority**: The declarative surface that makes the engine reusable; ships with the
engine but is validated on its own.

**Independent Test**: Load each of the four preset policies; confirm the parser extracts the
directives, rejects unknown keys, and that flipping `on_conflict = reject` changes the ingest
outcome for the same input.

**Acceptance Scenarios**:

1. **Given** a `policy.md`, **When** loaded, **Then** directive blocks under `##` sections are
   parsed, prose is ignored, and `#` inline comments + `*` wildcards are supported.
2. **Given** an unknown key or an `on_overlap` source collision, **When** loaded, **Then** the
   policy fails validation at load time.

### Edge Cases

- Two authored values arrive in a single fresh sync with no prior incumbent → contested read
  returns `both-contested` (or `unknown` — see research open question).
- Entity aliasing (`Acme` / `Acme Corp` / `Acme, Inc.`) and predicate synonymy (`employer` /
  `currentEmployer` / `worksAt`) must not slip past the gate as false "new" facts.
- Local small models (1–3B) produce weaker structured output for `GraphExtractorAgent` and
  `SearchTypeClassifierAgent` → need retry/repair or a smaller-schema fallback in dev.
- A derived fact inferred *using* a now-deleted authored edge (derivation lineage) — retract
  or merely weaken? (open question).
- Vectors from different embedding models are not comparable → dev and prod stores are not
  interchangeable; switching requires re-embedding.

## Requirements *(mandatory)*

### Functional Requirements

**Local / embedded models (Workstream A)**
- **FR-A1**: System MUST select models by presence of `GOOGLE_AI_GEMINI_API_KEY` — absent →
  local, present → Gemini — with no code edit to switch.
- **FR-A2**: Dev/local mode MUST make no outbound network calls at inference time (one-time
  weight download at setup is allowed).
- **FR-A3**: System MUST expose an `Embeddings` interface with `LocalOnnxEmbeddings` and
  `GeminiEmbeddings` implementations; the existing static embedder is refactored behind it.
- **FR-A4**: Local LLM MUST be wired through Akka's `ModelProvider` (not bypass the SDK).
- **FR-A5**: System MUST tag each store with its embedding-model identity and warn/refuse when
  the active model differs (vectors are not cross-model comparable).
- **FR-A6**: Structured-output call sites MUST degrade gracefully on weak local models
  (retry/repair or reduced schema); `QaAgent` free-text output is unaffected.

**Source separation (Workstream B)**
- **FR-B1**: `vault/**` MUST map to the authored layer and `corpus/**` to the derived layer;
  layer is a function of source location, assigned at sync, immutable per assertion.
- **FR-B2**: A path claimed by both layers MUST fail the sync (`on_overlap = error`).
- **FR-B3**: A `vault/` file MAY propose triples from prose, but they persist only when
  promoted to frontmatter; nothing derived ever persists *from* a vault file.
- **FR-B4**: A `corpus/` upload that is substantially a copy of a `vault/` file MUST be
  rejected (vault-leak guard).
- **FR-B5**: Separation of sources MUST NOT isolate facts — derived facts land in the same
  graph and are reconciled against authored facts.

**Conflict engine (Workstream C)**
- **FR-C1**: Every stored triple MUST carry a provenance envelope `{layer, source, asserted, conf}`.
- **FR-C2**: Predicates MUST be typed `functional` or `multi_valued`; `multi_valued` never
  conflicts.
- **FR-C3**: A conflict MUST be detected when two triples share subject+predicate on a
  `functional` predicate with different objects.
- **FR-C4**: Resolution MUST run a lexicographic cascade `layer-precedence → source-priority →
  confidence → recency → terminal action`, first strict winner wins. Each rung MUST be consulted
  strictly before lower rungs (a decisive higher rung pre-empts lower ones).
- **FR-C4a**: **(Model 1)** A cross-layer disagreement MUST resolve by layer-precedence — the
  authored value is served — with the derived loser recorded and **flagged for review**. It is
  RESOLVED, not CONTESTED; `freeze-incumbent` MUST NOT apply cross-layer.
- **FR-C5**: The cascade MUST be layer-aware — recency may break derived ties but never
  authored ties.
- **FR-C6**: At an authored tie with no discriminator (a genuine no-winner), the terminal action
  MUST be `flag` → CONTESTED, never `most-recent`.
- **FR-C7**: A **CONTESTED** functional predicate (cascade produced no winner) MUST read as
  `freeze-incumbent` when a prior
  value existed, else `both-contested`; never silently flip or blank.

**Ingest gate (Workstream D)**
- **FR-D1**: Each corpus-derived triple MUST be classified against the authored layer into
  exactly one of: **new** (persist derived), **corroborating** (no duplicate; attach as
  evidence), **conflicting** (flag; never block).
- **FR-D2**: The system MUST NEVER block a contradiction; "block content already in authored
  facts" applies only to the corroborating case.
- **FR-D3**: File-level hard blocks MUST include `on_exact_file_dup` (content hash) and the
  vault-leak guard.
- **FR-D4**: Ingest MUST emit a report: "N facts: X new, Y corroborating, Z conflicting."
- **FR-D5**: The gate MUST perform entity + predicate resolution at a configurable level
  (`strict | alias-aware | embedding-assisted`).

**Deletion (Workstream E)**
- **FR-E1**: A deleted/tombstoned fact MUST stop asserting and MUST NOT conflict.
- **FR-E2**: `on_source_removal` MUST support `cascade | orphan | freeze | archive`.
- **FR-E3**: Re-extraction of a deleted authored fact MUST be governed by `re_extraction`
  (`overwrite | append-versioned | diff-add-only | require-review`) and surfaced, not silent.
- **FR-E4**: Negations MUST be first-class authored facts; the corpus never negates.

**Policy format (Workstream F)**
- **FR-F1**: `policy.md` MUST be markdown with indented `key = value` directives; prose is
  ignored by the parser.
- **FR-F2**: The parser MUST support `##` section blocks, `#` inline comments, and `*`
  wildcard defaults, and MUST reject unknown keys and contradictory/collision directives at
  load time.
- **FR-F3**: Four preset policies MUST ship as examples/tests: personal assistant,
  compliance/legal, CRM/enrichment, eng runbook.

**Cross-cutting**
- **FR-X1**: Both ingest paths (prose via `/api/remember`, vault/corpus directory sync) MUST
  land in one Fluree store with per-triple provenance (named graphs `authored:` / `derived:`).
- **FR-X2**: An author-time lint MUST catch authored-vs-authored conflicts before sync.
- **FR-X3**: The durable write path (`RememberWorkflow`) crash-safety MUST be preserved; a
  decision MUST be recorded on whether the inline endpoint + read retrievers need durability
  once models can be local.

### Key Entities

- **Triple**: one fact `subject → predicate → object`, the unit of conflict.
- **Provenance envelope**: `{layer (authored|derived), source (file), asserted (timestamp),
  conf (0..1)}` attached to every triple.
- **Layer**: `authored` (from `vault/`) or `derived` (from `corpus/`); a function of source
  location.
- **Predicate cardinality**: `functional` (single-valued, can conflict) vs `multi_valued`.
- **Conflict / contested state**: a functional subject+predicate with divergent objects;
  carries `contested`, `contestedWith`, and a reason.
- **Negation**: a first-class authored fact asserting a negative.
- **Policy**: the parsed `policy.md` — sources, precedence, cardinality, resolution, ingest
  gate, deletion, provenance directives.
- **Ingest report**: per-sync counts of new / corroborating / conflicting facts.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A fresh checkout with no API key completes a `/remember` → `/recall` round-trip
  with zero inference-time network calls.
- **SC-002**: For a fact present in both `vault/` and `corpus/`, the store holds exactly two
  triples with correct, distinct provenance layers.
- **SC-003**: A vault/corpus contradiction on a functional predicate is always surfaced
  (flagged + reported) and never blocked or silently overwritten — 0 silent overwrites across
  the test suite.
- **SC-004**: An exact vault/corpus match produces 0 duplicate triples and 1 corroboration
  record.
- **SC-005**: An authored-vs-authored tie with no discriminator resolves to `flag` in 100% of
  cases (never recency-picked).
- **SC-006**: Each of the 4 preset policies loads, validates, and changes at least one ingest
  outcome for the same input, proving behavior is data-driven.
- **SC-007**: Deleting an authored fact never silently resurrects it as authored; every
  re-extraction attempt appears in the ingest report.
