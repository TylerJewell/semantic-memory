# Knowledge Model, Source Separation & Conflict Resolution — Design

**Status:** Design direction. Not committed to implementation.
**Last updated:** 2026-07-05
**Origin:** Exploratory design sessions (2026-07-04 → 2026-07-05) evolving `semantic-memory`
toward a hybrid Vault-LD + Cognee model with a declarative conflict-resolution policy.

---

## 1. Context and thesis

We are evolving this project toward a **hybrid** knowledge system:

- **Cognee-style prose ingestion** — LLM extraction of facts from arbitrary text
  (the existing `GraphExtractorAgent` pipeline).
- **Vault-LD-style authored knowledge** — markdown files with YAML-LD frontmatter as
  *source of truth*; RDF/graph is a *projection* of the files, not the master copy.
  (Ref: Tony Seale's Vault-LD, github.com/The-Knowledge-Graph-Guys/vault-ld.)

The two are complementary at the data-structure level, not competitive. Both land facts
into one Fluree store, where every fact carries provenance.

**The white space this design targets:** a declarative, fact-level **conflict-resolution
layer** — the part that decides *which facts are true when sources disagree, and who
decides.* Databricks, Snowflake, and Elasticsearch do not build this; they assume data is
reconciled upstream and express conflict imperatively (SQL `MERGE`, SCD Type 2, or
last-write-wins by document id). Even the closer GraphRAG / agent-memory category
(Neo4j, Cognee, Zep/Graphiti, Letta) largely hardcodes "latest fact wins." A **declarative,
user-editable conflict policy** is underserved even there.

---

## 2. The unit of knowledge: the triple

A **triple** is one fact split into three parts — `subject → predicate → object`
("Tyler worksAt Acme"). From RDF. Triples compose into a graph because the object of one
can be the subject of another (`Tyler → worksAt → Acme → locatedIn → Portland`).

Why the triple (not the warehouse row) is the right unit for us: **conflict happens at the
fact level, and a triple *is* a fact.** A row bundles many facts into a rigid pre-declared
shape; a triple stores one fact, shapelessly, and gives every fact its own identity,
timestamp, and source — exactly what a conflict policy needs to operate on.

Each stored triple carries a **provenance envelope** (native in Fluree, not bolted on):

```
Tyler — currentEmployer→ Acme
  { layer: authored, source: vault/people/tyler.md, asserted: 2026-07-01, conf: 1.0 }
```

Fields: `layer` (authored | derived), `source` (originating file), `asserted` (timestamp),
`conf` (extraction confidence; 1.0 for authored).

---

## 3. Hard source separation (an enforced invariant)

**A document is either curated or mined — never both. And extraction never persists a fact
back into the curated set.**

A single file that carries authored triples in frontmatter *and* prose that gets mined for
derived triples is an anti-pattern: it self-contradicts, and deletion becomes incoherent
(delete the frontmatter line, but the prose still says it → resurrection). So separation is
structural, by directory, enforced at sync time:

```
vault/     AUTHORED. Curated triples you stand behind. Git-reviewed. Small.
corpus/    DERIVED source material. Raw, mined, never hand-curated. Large, messy.
```

The two have genuinely different lifecycles:

| | `vault/` (authored) | `corpus/` (derived source) |
|---|---|---|
| Who writes it | a human, deliberately | anything — Slack, meetings, PDFs, tickets |
| Trust | source of truth | unverified raw material |
| Review | git-diffed, curated | append-and-forget |
| Ever edited to fix a fact? | yes | **never** — you don't edit a transcript |

**Layer = a function of source location.** `vault/` → authored, `corpus/` → derived.
Provenance stops being a guess.

**Extraction-as-authoring-assist:** a `vault/` file may *propose* triples from its prose,
but those persist only once a human promotes them into frontmatter (at which point they are
authored). **Nothing derived ever persists *from* a vault file.** The "curated wiki page
that also wants auto-extraction" case resolves here — the page is authored; extraction is a
transient suggestion, not a persisted derived triple.

### Separation of *sources* ≠ isolation of *facts*

Separation does **not** eliminate cross-layer conflict, and must not. A `corpus/` document
deriving `Tyler → currentEmployer → Globex` while `vault/` asserts `Acme` still collides —
**and that collision is the product.** The derived layer is a mirror held up to curated
truth: *"your raw documents claim something your vault doesn't."* That is how you learn your
vault is stale or a document is wrong. Quarantining derived facts from authored subjects
would delete the value of extraction and reduce the corpus to a search index.

The clean boundary is at **ingest** (one file, one role); reconciliation happens at the
**graph** (all facts compared under the policy).

---

## 4. The layer model & provenance

Two persisted layers: **authored** (from `vault/`) and **derived** (from `corpus/`).
Authored generally outranks derived, but precedence is a *cascade*, not a single rule
(see §5). Every triple's `layer` is set by its source directory and is immutable for that
assertion.

---

## 5. Conflict resolution — the resolution cascade

A conflict exists when two triples share **subject + predicate** and the predicate is
`functional` (single-valued). `multi_valued` predicates never "conflict" — multiple objects
coexist by design.

Resolution is a **lexicographic cascade of discriminators**, tried in order until one
produces a strict winner. If none does, a **terminal action** fires.

```
layer-precedence  →  source-priority  →  confidence  →  recency  →  (no winner → terminal action)
```

**The cascade is layer-aware.** Recency is a legitimate discriminator for *derived* facts
(a newer extraction supersedes an older guess) but **illegitimate for authored facts** (two
deliberate human statements — newer does not mean correct). Design principle:

> A machine-generated guess has no dignity to protect, so auto-picking the newest / most
> confident one is fine. A human assertion does — so when the cascade runs out of
> *meaningful* discriminators, the machine must **escalate, not guess.**

### The authored-tie rule

When two **authored** facts conflict and neither `layer-precedence` nor `source-priority`
can break the tie, the terminal action is **`flag`, never `most-recent`.** Silently picking
the newest file mtime discards a trusted human fact and makes "source of truth" depend on
edit order / git churn. **The system's job at an authored tie is not to resolve it — it is
to refuse loudly** (and, ideally, catch it earlier as an author-time lint; see §8).

### Query-time semantics for a contested fact

While a functional predicate is `contested` (flagged, unresolved):

| Situation | Read returns |
|---|---|
| A prior value existed before the conflicting one arrived | **freeze-incumbent** — the pre-conflict value, tagged `contested` |
| Both arrived in one sync, no prior value | **both-contested** — return both, tagged |

Never silently flip; never silently blank.

---

## 6. Deletion — retraction, negation, resurrection, lineage

Deleting a fact from an authored file means the fact **stops asserting** — whether the
triple is removed (`cascade`) or kept as an inactive tombstone (`freeze`/`archive`). A
tombstone does not assert, so **a deleted/tombstoned fact cannot conflict.** (Correcting an
earlier misframing: "a derived fact contradicting a *deleted* authored fact" is not a
conflict.)

The genuinely hard problems in the deletion axis are elsewhere:

1. **Resurrection via re-extraction.** Delete `Acme` from `vault/`, but a `corpus/` note
   still says it, so the extractor re-derives `Acme` as a *derived* fact. Under clean source
   separation this is *correct behavior*, reframed as a feature: *"you dropped Acme from
   curated truth, but N source documents still assert it — are you sure?"* Governed by the
   re-extraction policy (`overwrite` / `append-versioned` / `diff-add-only` /
   `require-review`).

2. **Derivation-lineage cascade.** If a derived fact was *inferred using* a now-deleted
   authored fact (e.g. `worksInState → Oregon` derived by walking `currentEmployer →
   Acme → locatedIn → Portland → inState → Oregon`), deleting the source edge leaves the
   downstream conclusion unsupported. The real cascade is **through the provenance/derivation
   graph**, not through contradiction.

3. **Retraction vs. negation (structural gap).** Deleting a line cannot distinguish
   *"I no longer assert Tyler works at Acme"* (retraction — absence of assertion) from
   *"Tyler does **not** work at Acme"* (negation — a positive negative fact). A deleted line
   looks identical either way, yet they behave oppositely downstream (under retraction a
   re-derived `Acme` is fine; under negation it must be actively suppressed). Vault-LD's
   file model is weakest here. **Resolution:** negations are first-class **authored facts in
   `vault/`** — you write "Tyler does not work at Acme" explicitly; the corpus never negates,
   it only positively reports what a document said.

---

## 7. The ingest gate — dedup, don't blind

When a `corpus/` document is extracted, each derived triple is measured against the authored
layer. **Three** outcomes:

| Outcome | Derived triple vs authored | Action |
|---|---|---|
| **New** | subject/predicate not authored | persist as `derived` |
| **Corroborating** | exact match to an authored fact (same S-P-O) | **do not persist a duplicate** — attach as corroboration/evidence |
| **Conflicting** | same S-P, *different* object | **flag for review — never block** |

**Critical distinction: redundant vs contradictory.**
- *"Block content already in authored facts"* means the **corroborating** row only — a
  derived triple that merely re-states an authored fact adds no information, so don't store a
  competing copy.
- It must **never** be read as "block anything touching an authored subject," because that
  swallows the **conflicting** row — the exact signal the whole design exists to surface.
  Blocking a contradiction builds a mirror that only reflects what you already believe.

Rule: **suppress facts that agree with the vault; surface facts that disagree with it.**

Even for the corroborating case, prefer **record-as-evidence** over discard: "11 source
documents corroborate `Tyler → currentEmployer → Acme`" is a confidence signal and a
staleness defense. "Block" = don't create a duplicate competing triple, not throw the
observation away.

### Hard dependency: entity + predicate resolution

The gate is only as good as resolution. Exact-triple match is trivial, but real input brings
`Acme` vs `Acme Corp` vs `Acme, Inc.` (entity aliasing) and `employer` vs `currentEmployer`
vs `worksAt` (predicate synonymy). Without resolution, paraphrases slip past "already
authored" as false "new" facts or false "conflicts." Resolution quality *is* gate quality;
it is its own policy axis (`resolution = strict | alias-aware | embedding-assisted`).

### File-level hard blocks (separate from the fact-level gate)

1. `on_exact_file_dup = reject` — same content hash already ingested. Unambiguous junk.
2. **Vault-leak guard** — a `corpus/` upload that is substantially a copy of a `vault/`
   file. Authored material sneaking in the derived door; reject to preserve one-file-one-role.

---

## 8. The `policy.md` file format

**Markdown with embedded `key = value` directives.** Prose sections carry the *why*
(readable top-to-bottom, doubles as documentation); indented directive blocks carry the
machine-parseable *what*. Greppable, no YAML-indentation gotchas, and a non-technical reader
can grasp the config's "personality" by reading it like a document. Chosen over nested YAML
(too many dimensions, too nested) and pure dot-syntax INI (no room to explain intent).

### Consolidated annotated example

```markdown
# Memory Policy — Personal Assistant

This is my second brain. Ingest generously, never lose a note, and interrupt me
only when two things genuinely contradict.

## Sources
Every path is bound to exactly one layer. A file belongs to one source.
An overlap is a configuration error, not a runtime guess.

    authored = vault/**
    corpus   = corpus/**
    on_overlap = error                  # a path claimed by both layers → refuse to sync

    authored.extraction = assist-only   # vault prose may PROPOSE triples, but they persist
                                         # only once promoted to frontmatter

## Precedence
Higher-precedence layers win. Within a layer, a source ordering breaks ties
meaningfully (not by timestamp).

    precedence      = authored > derived
    source_priority = vault/people/ > vault/orgs/ > vault/imports/

## Cardinality
Most relationships are many. Only a few facts are truly singular.

    functional   = birthDate, homeCity, currentEmployer
    multi_valued = *                    # default: everything else is multi

## Resolution
The cascade: try each discriminator in order; first strict winner wins.
If none decides, apply the terminal action. The cascade is layer-aware —
recency may decide derived ties but NEVER authored ties.

    resolve_by.authored = layer-precedence, source-priority          # then STOP → flag
    resolve_by.derived  = layer-precedence, source-priority, confidence, recency

## Conflict Resolution
Terminal action when the cascade cannot decide, plus contested-read behavior.

    on_conflict     = flag              # keep both, surface for review
    scope           = functional-only   # multi-valued never "conflicts"
    contested_read  = freeze-incumbent  # else both-contested if no prior value

## Ingest Gate (corpus only)
Dedup redundant confirmations; surface conflicts; never block a contradiction.

    on_exact_file_dup    = reject       # same content hash already ingested → block upload
    on_vault_leak        = reject       # corpus file that is really a vault file → block
    on_authored_match    = corroborate  # derived == authored → attach as evidence, no duplicate
    on_authored_conflict = flag         # derived contradicts authored → surface, NEVER block
    report               = summary      # "17 facts: 4 new, 11 corroborating, 2 conflicting"

## Resolution Quality
    resolution = alias-aware            # strict | alias-aware | embedding-assisted

## Deletion
    on_source_removal = orphan          # cascade | orphan | freeze | archive
    re_extraction     = require-review  # overwrite | append-versioned | diff-add-only | require-review
    negation          = authored-only   # negations are first-class authored facts; corpus never negates

## Provenance
    retention = full                    # who/what/when for every triple
```

### One-line change moves between "personalities"

The same graph and pipeline stay identical; only the policy changes:

| Edit | Effect | Profile |
|---|---|---|
| `on_conflict = reject` + `derived_may_assert = false` | contradictions refused; derived is suggestion-only | Compliance / legal KB |
| `on_conflict = replace`, drop precedence, add `ttl` + `min_confidence` | last-(best)-write-wins, stale facts age out | CRM / enrichment |
| `derived_vs_authored = reject`, `re_extraction = require-review` | logs can't overrule the wiki; re-mined facts queue | Eng runbook |
| default (above) | flag + orphan + corroborate | Personal assistant |

---

## 9. Invariants (write these down)

1. **A document is either curated or mined, never both; extraction never persists a fact
   back into the curated set.**
2. **Layer is a function of source location**, enforced at sync.
3. **Suppress facts that agree with the vault; surface facts that disagree with it.**
   Never block a contradiction.
4. **At an authored tie, refuse loudly (`flag`) — never auto-pick by recency.**
5. **A deleted/tombstoned fact stops asserting and cannot conflict.**
6. **Negations are first-class authored facts**, not the absence of an assertion.

---

## 10. Open questions (not yet decided)

- Derivation-lineage cascade: how deep, and does removing a support edge retract or merely
  *weaken* (lower confidence on) downstream derived facts?
- Contested-read default when a genuinely fresh sync introduces two authored values at once —
  is `both-contested` acceptable to downstream consumers, or is `unknown` safer?
- Where the author-time lint runs (pre-commit hook vs. pre-sync) and how it reports.
- Exact shape of the resolution-quality tiers and their cost/latency trade-offs.
- Whether `source_priority` should be a total order or allow explicit "equal priority"
  buckets (which then always `flag`).
```
