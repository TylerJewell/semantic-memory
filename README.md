# semantic-memory

A provenanced fact store with a declarative conflict-resolution layer.

Every fact records who asserted it and where from. When two sources disagree, a
defined precedence order decides which value is served — and surfaces the
disagreement rather than hiding it.

Built on the **Akka Java SDK** for the service and **Fluree** as the store.

## The problem

Most knowledge stores treat every fact as equal and anonymous. When two sources
assert different values for the same thing, the store either overwrites silently
(last write wins) or requires reconciliation upstream. Neither records why one
value was chosen, and neither tells you a conflict occurred.

## What this does

One primitive — the provenanced assertion:

```
Assertion { subject · predicate · object
            layer   authored | derived
            source  where it came from
            asserted · confidence · active }
```

Two ways in, one graph:

```
prose  ──/remember──▶  extract ─┐
                                ├──▶  assertion  ──▶  reconcile
files  ──/sync─────▶  parse ────┘
```

Structured reads out:

```
GET /fact            the served value for a subject + predicate, with provenance
GET /disagreements   where a lower-precedence source disagrees with the served value
GET /conflicts       ties the system will not resolve on its own
```

## How conflicts resolve

A fixed precedence order, tried in sequence until one produces a winner:

```
layer-precedence  →  source-priority  →  confidence  →  recency
```

- **RESOLVED** — a winner is served. Cross-layer losers are recorded and flagged
  for review.
- **CONTESTED** — no winner (e.g. two authored values of equal priority). Both are
  kept, the served value is frozen, and a human decides.

Authored facts (curated) outrank derived facts (mined). Recency may break a tie
between two mined facts; it is never used to overrule a human assertion. On an
unresolvable tie the system flags rather than guesses.

## Properties

- **Provenance.** Every fact carries its layer, source, and confidence.
- **Never blocks.** A contradicting fact is stored and surfaced, not dropped.
- **Deletion is scoped.** Deleting a fact scans for dependents (facts inferred
  from it, rivals it was outranking, sources that re-assert it) and asks before
  removing them.
- **Declarative policy.** Precedence, cardinality, and ingest-gate behavior are
  set in a readable `policy.md`, not in code.
- **Runs without keys.** An in-JVM model handles extraction; no external account
  or network is required on the ingest path. Supply a Gemini key to use a hosted
  model instead.

## How it was built

Built with [Akka Specify](https://doc.akka.io/sdk/spec-driven-development.html) and Fluree, using loop-driven
engineering and spec-driven development. Requirements were captured as an
executable specification and driven to completion in a loop: each requirement is
a checkable condition, and the build advances by running the checks, resolving
failures, and repeating until every condition holds.

See `specs/001-knowledge-conflict-resolution/` for the specification, the
conditions (`CONFORMANCE.md`), and the architecture (`architecture.md`).

## HTTP API

| Method + path          | Query / body                     | Returns |
| ---------------------- | -------------------------------- | ------- |
| `POST /api/remember`   | `{text}`                         | extracted graph + ingest report |
| `POST /api/sync`       | authored/corpus facts + globs    | ingest report (409 on overlap, 422 on vault-leak) |
| `GET  /api/fact`       | `?subject=&predicate=`           | served value + provenance, or CONTESTED / UNKNOWN |
| `GET  /api/disagreements` | —                             | facts where a lower-precedence source disagrees |
| `GET  /api/conflicts`  | —                                | unresolved ties awaiting a human |
| `GET  /api/stats`      | —                                | counts over stored assertions |
| `GET  /api/recent`     | —                                | recent assertions |
| `POST /api/forget`     | `{}`                             | clears the store |

## Prerequisites

- Java 21+ and Maven 3.9+.
- The Fluree binary — download from
  [fluree/db releases](https://github.com/fluree/db/releases).
- Optional: a Google Gemini API key exported as `GOOGLE_AI_GEMINI_API_KEY`. Absent,
  the service uses an in-JVM model (downloaded once on first ingest, ~1 GB).

## Quick start

```bash
./start.sh        # or start.bat on Windows
```

`start.sh` initializes Fluree, launches its HTTP server on `127.0.0.1:8090`,
creates the `memory` ledger, then builds and runs the service. With no
`GOOGLE_AI_GEMINI_API_KEY` set, ingest runs on the local in-JVM model.

## Repository layout

```
src/main/java/com/example/
  api/                  HTTP endpoints (MemoryEndpoint, UiEndpoint)
  application/
    conflict/           resolution cascade, reconciler, contested reads, entity resolution
    ingest/             ingest gate, triple store, tombstones
    deletion/           deletion-impact scan
    policy/             policy.md parser + author-time lint
    sync/               source-layer resolver, vault/corpus sync
    model/              key-gated model selection, in-JVM Jlama adapter
    embeddings/         embeddings SPI (local ONNX / Gemini)
    GraphExtractorAgent, RememberWorkflow, FlureeClient
  domain/               Triple, ProvenanceEnvelope, Layer, Cardinality
src/main/resources/
  policies/             preset policy files
conformance/            the conform runner + receipt log
specs/                  specification, exit conditions, architecture
```

## License

**This project's own source code is MIT.** See `LICENSE`.

That covers only the code in this repository. The runtime depends on third-party
components with their own licensing terms, which are **not** covered by the MIT
license here and must be obtained separately by anyone deploying this stack:

- **Akka SDK / Akka runtime** — [Business Source License 1.1](https://www.akka.io/bsl-license)
  by Akka Inc. Development, testing, and small-scale use are free; commercial or
  production deployments above the BSL threshold require a commercial license from
  Akka Inc. See <https://www.akka.io/pricing>.
- **Fluree DB** — the open-source Fluree DB binary is released under the
  [Eclipse Public License 2.0](https://github.com/fluree/db/blob/main/LICENSE).
  Fluree's hosted / commercial products have separate terms from
  [Fluree PBC](https://flur.ee/).
- **Jlama** — used for in-JVM inference; see the
  [Jlama project](https://github.com/tjake/Jlama) for its terms.

If you fork or deploy this project you are responsible for holding valid licenses
for these dependencies. This repository's MIT grant does not — and cannot —
sublicense them.
