# Glossary — Knowledge Conflict Resolution

> **Tier:** authoritative (specs/)
> **Purpose:** single definition of every canonical term; the terminology check (EC-D05) enforces these.
> **Audience:** anyone reading or writing feature-001 artifacts.
> **Lifecycle:** durable — terms change only by deliberate edit here.

## Glossary

- **Triple** — one fact `subject → predicate → object`; the unit of conflict.
- **Provenance envelope** — `{layer, source, asserted, conf}` attached to every stored triple.
- **Layer** — `authored` (from `vault/`) or `derived` (from `corpus/`); a function of source location.
- **Authored** — a curated fact from `vault/`; `conf = 1.0`.
- **Derived** — a mined fact from `corpus/`; carries extractor confidence.
- **vault/** — the authored source directory (source of truth).
- **corpus/** — the derived source directory (raw, mined, never hand-curated).
- **functional** — a single-valued predicate; two different objects for one subject conflict.
- **multi_valued** — a many-valued predicate; multiple objects coexist and never conflict.
- **Cascade** — the resolution order `layer-precedence → source-priority → confidence → recency → terminal`.
- **RESOLVED** — the cascade produced a strict winner; that value is served. A cross-layer
  loser is recorded and **flagged**, but the read is unambiguous.
- **flagged / disagreement** — a RESOLVED fact where a lower-precedence source disagreed;
  surfaced on `GET /api/disagreements` as a review/staleness signal, never blocked.
- **CONTESTED** — the cascade produced *no* winner (an authored tie, or same-layer with no
  discriminator). Never a cross-layer case — those are RESOLVED. Served per `contested_read`;
  surfaced on `GET /api/conflicts`.
- **freeze-incumbent** — a contested read that returns the pre-conflict value, tagged contested.
  Applies only to CONTESTED (never cross-layer).
- **Negation** — a first-class authored fact asserting a negative (e.g. `NOT(Tyler worksAt Acme)`).
- **Tombstone** — an inactive triple that no longer asserts and cannot conflict.
- **Ingest gate** — classifies each derived triple as **new | corroborating | conflicting**.
- **Exit Condition (EC)** — an evaluable `{id, invariant, signal, probe, pass, autonomy, status}`
  predicate; the source of "done."
