# Manifest

> **Tier:** authoritative (specs/)
> **Purpose:** Declares the authority order and inventory of artifacts for feature 001.
> **Audience:** Anyone deciding which document is the source of truth.
> **Lifecycle:** durable

## Authority

The `specs/` set is **authoritative** — it is the source of truth for this feature.
The `docs/` set is the **exploratory record**: it captures design rationale and handoff
notes, and is **superseded by `specs/` on any conflict**. When the two disagree, the
`specs/` document wins.

### Authoritative artifacts (`specs/`)

- `spec.md` — feature specification: scenarios, requirements, success criteria.
- `plan.md` — phased implementation plan and workstream sequencing.
- `research.md` — resolved open questions with decisions and rationale.
- `data-model.md` — entities, relationships, and the provenance envelope.
- `quickstart.md` — runnable acceptance walk-throughs, one per phase.
- `CONFORMANCE.md` — the durable "done" contract of Exit Conditions.
- `contracts/embeddings-spi.md` — the `Embeddings` interface and model selection.
- `contracts/http-api.md` — the `/api` HTTP endpoint surface.
- `contracts/policy-md.md` — the `policy.md` directive grammar.

### Exploratory record (`docs/`) — superseded by `specs/` on conflict

- `docs/policy-and-conflict-design.md` — full design rationale (the *why*).
- `docs/requirements-and-todos.md` — cross-machine handoff notes and decision history.
