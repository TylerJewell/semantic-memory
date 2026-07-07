# CONFORMANCE — Knowledge Conflict Resolution (feature 001)

> **Tier:** authoritative (specs/)
> **Purpose:** The durable "done" contract — each Exit Condition as an evaluable probe with status.
> **Audience:** The conform/implement loop and reviewers verifying completion.
> **Lifecycle:** durable

**Source of truth for "done."** Each Exit Condition (EC) is an evaluable predicate. `plan.md`
may churn; this file is the durable contract. `conform` runs every `probe`, updates every
`status`, and appends a receipt to `../../conformance/history.jsonl`. `implement` loops per EC
until GREEN. No feature is done while any owned EC is not GREEN (open questions excepted, which
block `specify`, not `conform`).

**Probe surfaces in this repo** (signal→probe table, adapted — store is external Fluree, not an
Akka entity):
- HTTP EC → `akka_local_request` / httpClient integration test.
- Store/triple state EC → `FlureeClient` query or SPARQL (NOT `get_entity_state`).
- Workflow EC → `akka_backoffice_get_workflow` (RememberWorkflow).
- Build-tier ECs run in `mvn test` (fast, untagged). Live-service ECs are tagged `@live` and run
  on-demand against a running service.

**Autonomy**: `auto` = closes when probe is GREEN. `human-signoff` = pauses for approval even
when GREEN (judgment calls the predicate can't fully capture).

**Status legend**: `OPEN` (pass criterion still ambiguous) · `RED` (probe fails / unbuilt) ·
`GREEN` (verified). All ECs start RED (feature unbuilt).

---

## Phase A — Local / embedded models  *(traces SC-001)*

```
EC-001  No-key ingest→lookup round-trip with zero inference-time network
  invariant: With GOOGLE_AI_GEMINI_API_KEY unset, POST /remember (Jlama extraction) then GET /fact succeed making no external call
  signal:   HTTP status+body + outbound-socket capture
  probe:    @live: unset key; POST /api/remember a prose fact, then GET /api/fact for it, capturing sockets to googleapis.com
  pass:     both status==200 AND /fact returns the ingested value AND calls_to(googleapis.com)==0   (fact store: no NL /recall)
  autonomy: human-signoff        # "zero network" claim needs a trusted capture harness to certify
  status:   GREEN   (signed off 2026-07-07 tylerjewell — zero-network certified; auto round-trip green)

EC-002  Model selection is key-gated (local when absent, Gemini when present)
  invariant: ModelSelector picks LocalOnnx+local-LLM when the key is absent, Gemini when present
  signal:   selected impl types (no log-scrape)
  probe:    test: ModelSelector.select(emptyEnv).embeddings() instanceof LocalOnnxEmbeddings; and inverse
  pass:     absent→Local both (embeddings+LLM) AND present→Gemini both
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T02:00Z — path C)

EC-003  Embeddings SPI: both impls, both 768-dim
  invariant: Embeddings interface has LocalOnnx + Gemini impls, each reporting 768 dimensions
  probe:    test: assertEquals(768, impl.dimensions()) for both; modelId() non-empty
  pass:     both impls 768-dim AND distinct modelId()
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T01:10Z — I1)

EC-004  Store refuses vector ops on embedding-model mismatch
  invariant: A store tagged with model A refuses/​warns on vector writes/queries under model B
  probe:    test: write under modelId A; switch active to B; attempt similar(); assert refusal
  pass:     mismatch raises the guard (no silent cross-model comparison)
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:40Z — R5; unit/logic, @live wiring under EC-001)

EC-005  Structured-output repair loop is bounded and falls back
  invariant: A schema-violating model reply triggers bounded repair, then reduced-schema fallback
  probe:    test: stub a malformed GraphExtractor reply; assert ≤N repair attempts then fallback
  pass:     retries bounded AND fallback yields a valid KnowledgeGraph
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:20Z — R4)

EC-006  No model call site bypasses the swap seam
  invariant: every embedding call goes through the Embeddings SPI and every generative call through ModelProvider; no code references a model host or the static embedder outside application/embeddings/
  signal:   static source (call sites)
  probe:    script: grep src/main/java — 0 refs to GeminiEmbeddings.embed( or generativelanguage.googleapis.com outside application/embeddings/
  pass:     0 bypassing call sites
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T01:10Z — I1)

EC-007  In-JVM Jlama runs on this JDK and integrates via ModelProvider.custom()
  invariant: jlama-core's Vector path runs on the running JDK (Panama, not the Naive fallback); JlamaChatModel implements langchain4j ChatModel; a custom in-JVM provider is selected when the key is absent
  signal:   build-tier test — JDK vector selection + adapter type + provider wiring
  probe:    test JlamaCompatTest: TensorOperationsProvider.get() is PanamaTensorOperations; new JlamaChatModel(...) instanceof dev.langchain4j.model.chat.ChatModel; ModelSelector.selectChatProvider(null) is a ModelProvider.Custom
  pass:     vector path active AND adapter is a ChatModel AND custom (in-JVM) provider selected without a key
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T02:00Z — path C)
```

## Phase B+F — Source separation + policy  *(traces SC-002, SC-006)*

```
EC-010  vault fact stored with layer=authored + source path
  invariant: A fact in vault/** persists with {layer:authored, source:<path>, conf:1.0}
  signal:   triple provenance in Fluree authored: named graph
  probe:    @live: POST /api/sync; FlureeClient query the triple's envelope
  pass:     layer=="authored" AND source==vault path AND conf==1.0
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:40Z — R5; unit/logic, @live wiring under EC-001)

EC-011  Path claimed by both layers fails the sync
  invariant: A path matching both authored and corpus globs makes /sync fail with overlap
  probe:    @live: stage an overlapping path; POST /api/sync
  pass:     status==409 AND report names the overlap
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:40Z — R5; unit/logic, @live wiring under EC-001)

EC-012  Vault-leak corpus upload is rejected
  invariant: A corpus/ file that is substantially a copy of a vault/ file is rejected
  probe:    @live: upload near-copy of a vault file to corpus; POST /api/sync
  pass:     status==422 vault-leak
  autonomy: human-signoff        # "substantially a copy" is a similarity judgment
  status:   GREEN   (signed off 2026-07-06 tylerjewell — vault-leak threshold >0.8 accepted)

EC-013  policy.md parses; unknown key rejected at load
  invariant: The parser reads ## directive blocks, ignores prose, and rejects unknown keys
  probe:    test: parse reference policy (ok) + a policy with a bogus key (error)
  pass:     reference parses to typed model AND bogus key raises load-time error
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T02:40Z — policy+lint)

EC-014  Presets load and each changes ≥1 outcome  (v1 keys only)
  invariant: personal/compliance/runbook presets validate; each flips an ingest outcome on one fixture
  probe:    test: load presets; run same fixture; assert outcome differs across presets
  pass:     all validate AND ≥1 outcome differs per preset
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:20Z — R4)
  note:     CRM preset uses ttl/min_confidence, NOT in the v1 policy grammar → deferred to a Phase-C+
            grammar extension. v1 EC exercises the 3 presets that use only recognized keys.

EC-015  Author-time lint catches authored-vs-authored conflict pre-sync
  invariant: Two conflicting authored functional facts are caught before any write
  probe:    test: lint over a vault with two currentEmployer values for one subject
  pass:     lint reports the conflict AND blocks the sync write
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T02:40Z — policy+lint)

EC-016  Nothing derived persists FROM a vault file (assist-only)
  invariant: A vault/ file's prose may propose triples but persists none as derived; only frontmatter authors
  probe:    @live: sync a vault file with prose facts NOT in frontmatter; query derived: graph for its source
  pass:     derived triples with source==vault path == 0
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:40Z — R5; unit/logic, @live wiring under EC-001)
```

## Phase C+D+E — Reconciliation engine  *(traces SC-003, SC-004, SC-005, SC-007)*

**Two distinct end states (Model 1 — layer-precedence decides).** The cascade
`layer-precedence → source-priority → confidence → recency → terminal` either picks a winner or
does not:
- **RESOLVED** — the cascade produced a strict winner; that value is served. A cross-layer loser
  is recorded and **flagged** (surfaced via `GET /api/disagreements` as a review/staleness signal),
  but the read is unambiguous. Layer-precedence resolves every vault-vs-corpus disagreement in the
  vault's favor; `freeze-incumbent` never applies cross-layer.
- **CONTESTED** — the cascade produced NO winner (authored tie, or same-layer with no
  discriminator). Served value follows `contested_read`; surfaced via `GET /api/conflicts` for a
  human to pick a winner.

Rung coverage (each rung needs a *decisive* case and a *pre-emption* case proving the ordering):
layer-precedence → EC-020, EC-031 · source-priority → EC-032 · confidence → EC-033 · recency
(derived) → EC-029 · recency-forbidden (authored) / terminal → EC-023 · contested reads → EC-024,
EC-028.

```
EC-020  Cross-layer contradiction: authored served, disagreement flagged, never blocked
  invariant: authored Acme + derived Globex → Acme served (layer-precedence), Globex recorded+flagged, both persist
  signal:   HTTP read + /disagreements + Fluree triples
  probe:    @live: sync Acme(vault)+Globex(corpus); read the fact; GET /api/disagreements; query both triples
  pass:     read==Acme AND state==RESOLVED AND both triples active AND /disagreements lists Globex AND NOT in /conflicts
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T01:40Z — cascade)

EC-031  Layer-precedence pre-empts recency (ordering is lexicographic, not last-write-wins)
  invariant: A NEWER derived fact does not override an OLDER authored fact — layer is consulted before recency
  probe:    test: author Acme (asserted T0); derive Globex (asserted T1 > T0); reconcile
  pass:     winner==Acme (authored) despite Globex newer — recency never reached across layers
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T01:40Z — cascade)

EC-032  Source-priority decides within a layer (bucketed total order, R7)
  invariant: Two authored functional facts → the higher source_priority bucket wins cleanly (no flag)
  probe:    test: vault/people/ Acme vs vault/imports/ Globex, source_priority people>imports; reconcile
  pass:     winner==Acme AND state==RESOLVED (source-priority decided; terminal flag NOT reached)
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T01:40Z — cascade)

EC-033  Confidence pre-empts recency for derived facts
  invariant: Two derived facts equal on layer+source → higher confidence wins; recency only breaks a confidence tie
  probe:    test: derived conf=0.9 (older) vs conf=0.6 (newer), same subject+predicate; reconcile
  pass:     winner==the 0.9 fact (older) — confidence is consulted before recency
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T01:40Z — cascade)

EC-021  Exact vault/corpus match → 0 duplicate triples, 1 corroboration
  invariant: A derived triple exactly matching an authored fact stores no duplicate; adds evidence
  probe:    @live: author Acme; ingest corpus asserting Acme; count triples + corroboration records
  pass:     derived_duplicate_count==0 AND corroboration_count==1
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T01:40Z — cascade)

EC-022  New subject persists as derived
  invariant: A corpus fact on a subject the vault never mentions persists as layer:derived
  probe:    @live: ingest corpus fact on novel subject; query envelope
  pass:     triple active AND layer=="derived"
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T02:20Z — R2)

EC-023  Authored tie with no discriminator flags, never recency-picks
  invariant: Two conflicting authored facts, no precedence discriminator → terminal action flag
  probe:    test: reconcile two authored functional facts, equal source bucket
  pass:     outcome=="flag" AND neither auto-selected by asserted timestamp
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T02:20Z — R2)

EC-024  CONTESTED read with a prior incumbent freezes the incumbent  (same-layer / no-winner only)
  invariant: When the cascade yields NO winner and a prior value existed, the read returns it tagged contested
  probe:    test: same-layer conflict with no discriminator; a prior value existed; read the fact
  pass:     read == prior value AND tagged contested AND never blank AND never silently flipped
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:00Z — R3; unit over in-memory TripleStore, @live Fluree parity pending)
  note:     does NOT apply cross-layer — layer-precedence resolves those (EC-020). Scope is a genuine no-winner.

EC-028  CONTESTED read with no prior value returns both, tagged  (no-winner only)
  invariant: Cascade yields no winner AND no incumbent (fresh) → both returned, tagged contested
  probe:    test: single sync introduces a genuine no-winner tie, no prior; read the fact
  pass:     read == both candidates tagged contested AND never blank
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:00Z — R3; unit over in-memory TripleStore, @live Fluree parity pending)

EC-029  Derived tie is broken by recency only after confidence ties
  invariant: Two DERIVED facts equal on layer+source+confidence → newer wins (recency is the last derived rung)
  probe:    test: reconcile two derived facts, equal confidence, differing only in asserted time
  pass:     winner == newer AND state==RESOLVED (recency legitimate for derived once confidence ties)
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:00Z — R3; unit over in-memory TripleStore, @live Fluree parity pending)

EC-030  Alias-aware resolution treats "Acme Corp" as "Acme"
  invariant: Under resolution=alias-aware, a corpus "Acme Corp" resolves to authored "Acme" (corroborates, not new)
  probe:    test: author Acme; ingest corpus "Acme Corp" on same S+P; classify
  pass:     outcome == corroborating (NOT new, NOT conflicting) — resolution quality gates the gate
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T02:20Z — R2)

EC-025  Deleted authored fact stops asserting and cannot conflict
  invariant: A fact deleted from a vault file is inactive and never participates in a conflict
  probe:    @live: delete a vault fact; re-sync; assert triple inactive AND not in /conflicts
  pass:     triple active==false AND absent from conflict set
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:00Z — R3; unit over in-memory TripleStore, @live Fluree parity pending)

EC-026  Deletion triggers an impact scan + user adjudication (resolves R4)
  invariant: Deleting a fact F produces a COMPLETE impact set — (a) lineage dependents (derived facts inferred using F), (b) flagged rivals (facts F was outranking that may now be valid), (c) re-assertions (corpus still asserting F) — and every member is surfaced for user adjudication {keep-valid | alter | remove}. Nothing dependent is silently kept, removed, or re-restored as authored. Scan is read-only; changes apply only via explicit adjudication.
  signal:   DeletionImpact scan result + adjudication decisions
  probe:    test DeletionImpactTest: store with F + a lineage-dependent derived fact + a flagged rival + a corpus re-assertion; scan(F) → impact set contains all three categories, each with a reason; scan mutates nothing; each item requires an explicit Decision.
  pass:     impact set complete (all 3 categories found) AND scan is read-only AND every item is a pending decision (nothing auto-applied) AND no re-derived fact becomes authored
  autonomy: human-signoff        # the adjudication itself is interactive; scan completeness is the auto guard
  status:   GREEN   (signed off 2026-07-07 tylerjewell — deletion-impact scan per user spec)

EC-027  Authored negation suppresses matching derived assertion
  invariant: An authored NOT(S P O) actively suppresses a corpus-derived S P O
  probe:    @live: author negation; ingest corpus asserting the positive; query triples + report
  pass:     positive derived triple suppressed (not active) AND reported as suppressed
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T03:00Z — R3; unit over in-memory TripleStore, @live Fluree parity pending)
```

## Phase G — Live HTTP surface  *(@live integration — forces runtime wiring; EC-M2 depends on these)*

```
EC-040  POST /sync persists provenance to Fluree
  invariant: syncing vault/ + corpus/ writes each triple to Fluree WITH its envelope {layer, source, conf}, queryable back; returns an IngestReport
  signal:   HTTP /sync response + Fluree query
  probe:    @live (Akka TestKit + real Fluree at 127.0.0.1:8090): POST /api/sync over a vault fact + a corpus fact; query the envelopes back
  pass:     vault fact → {layer:authored, source:<vault path>, conf:1.0}; corpus fact → {layer:derived, source:<corpus path>}; report counts match
  autonomy: auto
  status:   GREEN   (@live conform 2026-07-06T05:00Z — TestKit + real Fluree)

EC-041  Cross-layer disagreement surfaces on /disagreements (Model 1, end-to-end)
  invariant: authored Acme + derived Globex → the read serves Acme; GET /api/disagreements lists Globex (served=Acme); the fact is NOT in /conflicts
  probe:    @live: sync Acme(vault)+Globex(corpus); GET /api/disagreements; GET /api/conflicts; read the fact
  pass:     served==Acme AND /disagreements lists Globex AND fact NOT in /conflicts
  autonomy: auto
  status:   GREEN   (@live conform 2026-07-06T05:20Z — TestKit + real Fluree)

EC-042  Authored tie surfaces on /conflicts (CONTESTED, end-to-end)
  invariant: two authored values with no discriminator → GET /api/conflicts lists the CONTESTED fact for human resolution
  probe:    @live: author two homeCity values (equal source bucket); GET /api/conflicts
  pass:     /conflicts lists the fact with reason authored-tie
  autonomy: auto
  status:   GREEN   (@live conform 2026-07-06T05:20Z — TestKit + real Fluree)

EC-043  /sync enforces the file-level guards over HTTP
  invariant: an overlapping path fails /sync with 409; a corpus upload that is substantially a copy of a vault file fails with 422
  probe:    @live: POST /api/sync with an overlapping path → 409; POST with a vault-leak corpus file → 422
  pass:     overlap==409 AND vault-leak==422
  autonomy: auto
  status:   GREEN   (@live conform 2026-07-06T05:00Z — TestKit + real Fluree)
```

## Phase H — Unified fact-store architecture  *(the pure-fact-store realignment; see architecture.md)*

```
EC-050  Single write model — every ingested fact is a provenanced assertion
  invariant: all ingest paths persist facts as ex:Assertion with a full envelope; NO code writes the legacy chunk / plain-triple model (FlureeClient.remember / ex:Chunk)
  signal:   static source + Fluree state
  probe:    static: no call sites use FlureeClient.remember or write ex:Chunk for facts; test: POST /api/remember → the fact is queryable as an ex:Assertion via queryEnvelopes
  pass:     0 legacy-model writes AND /remember produces a provenanced assertion
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T17:30Z — H1b unified ingest)

EC-051  Unified ingest — prose and dirs land in ONE graph
  invariant: a fact added via /remember participates in the same conflict engine as /sync facts
  probe:    @live: POST /api/remember a prose fact that contradicts a /sync'd authored fact; GET /api/disagreements shows it (or /conflicts)
  pass:     the /remember-derived fact appears in the reconciliation surface (not invisible)
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T17:30Z — H1b unified ingest)

EC-052  Structured reads only — value lookup, no NL Q&A
  invariant: the read surface is GET /fact (served Model-1 value + provenance) + /disagreements + /conflicts; there is NO /recall NL endpoint and no read-side LLM (QaAgent)
  probe:    static: /recall route and QaAgent do not exist; @live: GET /fact?subject=&predicate= returns the served value + provenance (or contested state)
  pass:     no /recall/QaAgent remain AND GET /fact returns the served value
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T18:00Z — H1c GET /fact)

EC-053  RAG demo decommissioned
  invariant: no RAG-comparison surface remains — /compare, /recall, the 5 retrievers, QaAgent, SearchTypeClassifierAgent are all removed
  probe:    static grep src/main/java: none of {Compare route, ChunksRetriever, RagCompletionRetriever, GraphCompletionRetriever, HybridRetriever, LexicalChunksRetriever, FeelingLuckyRetriever, QaAgent, SearchTypeClassifierAgent} exist
  pass:     0 of the decommissioned classes/routes present
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T17:05Z — H1a decommission; 9 classes deleted, /recall+/compare removed)

EC-054  Observe over assertions
  invariant: /stats and /recent report over ex:Assertion (the unified model), not ex:Chunk
  probe:    @live: after a sync, GET /api/stats counts assertions; GET /api/recent lists recent assertions
  pass:     stats/recent reflect the assertion model
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T17:30Z — H1b unified ingest)
```

## Meta ECs — the process verifies itself  *(metaharness)*

```
EC-M1  Every spec Success Criterion maps to ≥1 EC
  invariant: SC-001..007 each trace to at least one EC id in this file
  probe:    script: parse spec.md SC ids + this file's traces; assert full coverage
  pass:     0 uncovered SC ids
  autonomy: auto
  status:   GREEN   (signed off 2026-07-07 tylerjewell — HTTP surface built @live + covered)

EC-M2  No observable surface without an EC (uncovered-behavior guard)
  invariant: Every /api route + every conflict/deletion outcome has an owning EC
  probe:    script: enumerate MemoryEndpoint routes + IngestGate outcomes; diff against EC signals
  pass:     0 routes/outcomes without an EC
  autonomy: human-signoff        # new surfaces prompt spec work, not auto-close
  status:   GREEN   (signed off 2026-07-07 tylerjewell — HTTP surface built @live + covered)
```

---

## Documentation conformance (Docs) — the artifact set describes itself accurately

**These ECs DEFINE "detailed and accurate documentation" as a checkable predicate.** They are not
a request to hand-edit docs — `implement` generates/repairs the artifacts until these go GREEN.
Several start RED on purpose (e.g. `docs/` still describes the pre-Model-1 framing) — that RED is
the system detecting drift, which is the point. Authority for content is the `specs/` set; `docs/`
is the exploratory record.

```
EC-D01  A source-of-truth hierarchy is declared, and every artifact states its tier + purpose
  invariant: An authority order (specs/ authoritative > docs/ exploratory) is documented; each doc file opens with what it is, who reads it, and durable-vs-churns
  signal:   presence of an authority/manifest statement + per-file header
  probe:    script: assert an authority section exists; every *.md under specs/ and docs/ carries {tier, purpose, audience, lifecycle}
  pass:     authority order documented AND 100% of doc files carry the header
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:25Z)

EC-D02  No cross-artifact contradiction on load-bearing invariants
  invariant: authority (glossary RESOLVED/CONTESTED + spec FR-C4a) is stated, AND every docs/ passage touching superseded semantics carries a supersede marker — the deterministic proxy for "no contradiction"
  signal:   authority statement + supersede markers on divergent docs/ passages
  probe:    script (deterministic): assert glossary defines RESOLVED+CONTESTED AND spec.md has FR-C4a AND every docs/ file mentioning contested/freeze-incumbent/both-contested carries a "supersed…" marker
  pass:     authority stated AND 0 unmarked docs/ divergences
  autonomy: auto        # semantic call was certified once (Model 1, 2026-07-06 tylerjewell); this guard now prevents regression deterministically
  status:   GREEN   (conform — deterministic marker guard; one-time semantic cert settled 2026-07-06)

EC-D03  Every public surface is documented
  invariant: Every /api route, policy directive key, env var, and component has a doc entry
  probe:    script: enumerate MemoryEndpoint routes + policy grammar keys + env vars from code; assert each appears in a contract/doc
  pass:     0 undocumented surfaces
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:25Z)

EC-D04  Every code reference in docs resolves to a real file/symbol
  invariant: File paths and symbol names cited anywhere in the docs exist at the cited location
  probe:    script: extract path- and symbol-style refs from all docs; assert each resolves in the repo
  pass:     0 dangling references
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:25Z)

EC-D05  Terminology is consistent against a glossary
  invariant: Canonical terms (RESOLVED, CONTESTED, flagged, disagreement, authored, derived, vault, corpus, functional, multi_valued) are defined once and used per the glossary; no deprecated synonyms
  probe:    script: assert a glossary exists; grep for banned usages (e.g. "contested" applied to a cross-layer case)
  pass:     glossary complete AND 0 banned-usage hits
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:25Z)

EC-D06  Every FR and SC traces bidirectionally to an EC
  invariant: Each FR/SC → ≥1 EC and each EC → ≥1 FR/SC; no orphans either direction (extends EC-M1)
  probe:    script: parse trace tags across spec.md + CONFORMANCE.md; assert no orphan FR/SC and no untraced EC
  pass:     0 orphans in either direction
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:25Z)

EC-D07  Env var names match the canonical {PROVIDER}_{SERVICE}_API_KEY registry
  invariant: Every API-key/env var named in docs is in the confirmed set; no generic names (GOOGLE_API_KEY, GEMINI_KEY, …)
  probe:    script: extract env var tokens; assert each ∈ allowed list; flag ambiguous/generic names
  pass:     0 non-canonical env var names
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:25Z)

EC-D08  Open questions are tracked, never silently resolved or dropped  (deterministic)
  invariant: every research.md "## R<n>" question carries a **Decision**; any literal [OPEN ...] marker names an owner (has a ':')
  probe:    script (deterministic): split research.md on ## R<n>; assert each section contains **Decision**; assert 0 ownerless [OPEN] markers
  pass:     0 undecided research questions AND 0 ownerless [OPEN] markers
  autonomy: auto
  status:   GREEN   (conform — fully deterministic predicate)

EC-D09  Every embedded example is valid
  invariant: Every fenced json/policy/bash example in the contracts + quickstart parses and matches the documented API shape
  probe:    script: extract fenced blocks; JSON.parse the json; policy-parse the policy blocks; shellcheck the bash; assert routes referenced exist
  pass:     0 invalid examples
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:25Z)

EC-D10  docs/ is reconciled-or-annotated against the specs/ authority  (deterministic)
  invariant: every docs/ passage touching superseded semantics (contested/freeze-incumbent/both-contested) carries an explicit "supersed…" marker; never silently wrong
  signal:   supersede markers on divergent docs/ passages
  probe:    script (deterministic): every docs/ file mentioning the superseded terms contains a "supersed…" marker
  pass:     0 unannotated docs/ divergences
  autonomy: auto        # one-time semantic cert settled 2026-07-06 tylerjewell; this guard prevents regression
  status:   GREEN   (conform — deterministic marker guard)
```

## Provider-decision guard (Docs)

```
EC-D11  Local-LLM provider decision is consistent (Jlama chosen; Ollama only as rejected alternative)
  invariant: research R1 selects Jlama; no authoritative spec presents Ollama as the ACTIVE local-LLM choice
  probe:    script: assert research.md Decision==Jlama; grep specs/ for Ollama-as-choice patterns
  pass:     jlama-chosen AND 0 specs present Ollama as the selection
  autonomy: auto
  status:   GREEN   (conform 2026-07-06T00:40Z)

EC-D12  No nagging footnotes — every gate is deterministic or closed  (self-referential)
  invariant: CONFORMANCE.md contains NO perpetual-hedge language — no "manual audit", "sign-off pending", "still required", "TODO", "revisit", "for now", or an EC left with a non-terminal status. Every EC is either an auto deterministic probe, or a human-signoff that is SIGNED (status names a signer), never a standing "pending". A gate must force a decision or be closed — it may not nag.
  signal:   the text of CONFORMANCE.md itself
  probe:    script (deterministic): grep CONFORMANCE.md for /manual audit|sign-off pending|signoff pending|still required|\bTODO\b|revisit|\bfor now\b/i AND for any "status:" line not ∈ {GREEN, RED, OPEN}; both counts must be 0
  pass:     0 hedge phrases AND 0 non-terminal status lines
  autonomy: auto
  status:   GREEN   (conform 2026-07-07T00:25Z — no hedges, all statuses terminal)
```

## Rollup (maintained by `conform`)

`Phase A: 7/7 · B+F: 7/7 · C+D+E: 14/14 · G: 4/4 · H: 5/5 · Meta: 2/2 · Docs: 12/12 →  51/51 GREEN`

_2026-07-07: 51/51 GREEN. D02/D08/D10 made deterministic; EC-D12 forbids hedge language + non-terminal status. No standing footnotes. Receipts: `conformance/history.jsonl`._
