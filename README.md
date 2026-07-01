# semantic-memory

A durable, queryable knowledge base that AI agents can share — built on
**Akka SDK 3.6** for the control plane and **Fluree** as the unified graph +
vector + provenance store.

Ingest text or documents; the system extracts entities and relationships with an
LLM, embeds every chunk, and commits both the graph and the vector to Fluree in
a single cryptographically-recorded transaction. Ask a natural-language question
and pick one of five retrieval strategies — or run all of them side-by-side.

## Highlights

- **Two processes.** An Akka JVM service and the Fluree binary. That's the whole
  runtime footprint.
- **~550 MB resident.** Measured on a running instance (526 MB Akka + 24 MB
  Fluree).
- **~5 s cold start.**
- **Durable ingest pipeline.** The `RememberWorkflow` is an Akka SDK
  `Workflow` — a crashed step resumes at the failed step across a process
  restart. State is journaled, not "best-effort."
- **Five retrieval strategies** — `CHUNKS`, `RAG`, `GRAPH`, `HYBRID`,
  `FEELING_LUCKY`. A built-in compare-all mode runs every strategy on a single
  question in parallel and returns them side-by-side.
- **Cryptographic provenance.** Every `/api/remember` returns a Fluree commit
  hash. Fluree's time-travel queries reconstruct any prior state.
- **Native structured output.** The graph extractor uses
  `responseConformsTo(KnowledgeGraph.class)` — schema generation and validation
  are the Akka agent's job, not an external library.
- **No SaaS dependency.** No serverless queue, no vector-DB-as-a-service.
  Everything runs locally.

## Benchmark

10 items from the HotpotQA distractor validation split, 99 paragraphs ingested
(gold + distractors), one multi-hop question per item, scored with the official
HotpotQA token-level F1 + exact match. See `scripts/eval_adapter.py`.

| Strategy       | Exact Match | Token F1 | Mean latency |
| -------------- | ----------: | -------: | -----------: |
| CHUNKS (no LLM)| 0.00        | 0.01     | 2.3 s        |
| RAG            | 0.60        | 0.74     | 4.5 s        |
| GRAPH          | 0.70        | 0.84     | 4.4 s        |
| **HYBRID**     | **0.70**    | **0.88** | **4.6 s**    |
| FEELING_LUCKY  | 0.70        | 0.84     | 6.8 s        |

## Prerequisites

- Java 21+ and Maven 3.9+
- The Fluree binary — download from
  [fluree/db releases](https://github.com/fluree/db/releases)
- A Google Gemini API key exported as `GOOGLE_AI_GEMINI_API_KEY`
- The Akka SDK context docs (fetched via `akka specify init .` after installing
  the [Akka CLI](https://doc.akka.io/operations/cli/installation.html))

> ⚠️ **Licensing note.** This project's own code is MIT, but Akka SDK
> (Business Source License 1.1) and Fluree DB (EPL 2.0) are separate
> third-party components with their own terms. Anyone deploying this stack
> — especially for commercial or production use — is responsible for
> obtaining the appropriate licenses **directly from Akka Inc. and Fluree
> PBC**. See `NOTICE.md` for details.

## Quick start

```bash
export GOOGLE_AI_GEMINI_API_KEY=...
./start.sh        # or start.bat on Windows
```

`start.sh` initializes Fluree, launches its HTTP server on `127.0.0.1:8090`,
creates the `memory` ledger if it doesn't exist, then runs `mvn compile
exec:java`. Open **http://localhost:9000/** when the service says it's ready.

## UI

Two tabs:

- **App** — Remember / Recall / Compare-all. Live memory-counter chip, drag-drop
  `.txt`/`.md` import with progress bar, five selectable retrieval strategies,
  cosine-similarity score badges on retrieved context, single-click "clear
  memory."
- **Architecture** — side-by-side comparison of this stack against Cognee (a
  popular Python AI-memory framework), with measured footprint numbers and
  aligned diagrams.

## HTTP API

| Method + path         | Body                            | Returns                                                     |
| --------------------- | ------------------------------- | ----------------------------------------------------------- |
| `POST /api/remember`  | `{text}`                        | `{graph, commit}`                                           |
| `POST /api/recall`    | `{question, strategy?}`         | `{answer, context: [{text, score?}], strategy}`             |
| `POST /api/compare`   | `{question}`                    | `{question, results: [{strategy, answer, ms}]}`             |
| `POST /api/forget`    | `{}`                            | `{status: "cleared"}`                                       |
| `GET  /api/stats`     | —                               | `{chunks, entities, commits}`                               |
| `GET  /api/recent`    | —                               | `{chunks: [...]}`                                           |

`strategy` accepts `CHUNKS`, `RAG`, `GRAPH`, `HYBRID`, `LEXICAL`,
`FEELING_LUCKY` (case-insensitive; default is `RAG`).

## Evaluation

```bash
python -X utf8 scripts/eval_adapter.py --items 10
```

Fetches 10 HotpotQA dev-set distractor items, ingests all 99 paragraphs (gold +
distractor), asks the multi-hop question of every selected strategy, and prints
a table of exact-match, F1, and mean latency per strategy.

## Architecture in one paragraph

`RememberWorkflow` orchestrates the ingest: `GraphExtractorAgent` returns a
typed `KnowledgeGraph` from the LLM, `GeminiEmbeddings` produces a 768-dim
vector, `FlureeClient` commits both as a single JSON-LD transaction that
Fluree records with a cryptographic hash. On recall, the endpoint dispatches
to one of the five retrievers (or the `FeelingLuckyRetriever` classifier picks
one for you), each of which returns an `Answer` with cosine-scored evidence.
The QaAgent is instructed to be terse — HotpotQA answers are single entities
or `yes`/`no`, not sentences.

## Repository layout

```
src/main/java/com/example/
  api/                  HTTP endpoints (MemoryEndpoint, UiEndpoint)
  application/          Agents, workflow, Fluree/embeddings clients, retrievers
  domain/               KnowledgeGraph record — the extraction contract
src/main/resources/
  application.conf      Gemini model config (reads $GOOGLE_AI_GEMINI_API_KEY)
  static-resources/     The web UI (single index.html)
src/test/java/          JUnit tests against the running service
scripts/                Python HotpotQA eval harness
```

## Prior art & credit

The overall four-verb API surface (`remember` / `recall` / `forget` / `improve`)
and the strategy taxonomy borrow from
[Cognee](https://github.com/topoteretes/cognee), a Python AI-memory framework
whose eval framework was the reference for this project's benchmark harness.
The Architecture tab shows the footprint difference side-by-side.

## License

**This project's own source code is MIT.** See `LICENSE`.

That covers only the code in this repository. The runtime depends on two
third-party components with their own licensing terms, which are **not** covered
by the MIT license here and must be obtained separately by anyone deploying
this stack:

- **Akka SDK / Akka runtime** — released under the
  [Business Source License 1.1](https://www.akka.io/bsl-license) by Akka Inc.
  Development, testing, and small-scale use are permitted for free; commercial
  or production deployments above the BSL threshold require a commercial
  license obtained directly from Akka Inc. See
  <https://www.akka.io/pricing> for current terms.

- **Fluree DB** — the open-source Fluree DB binary this project uses (v4.1.1+)
  is released under the [Eclipse Public License 2.0](https://github.com/fluree/db/blob/main/LICENSE).
  Fluree's hosted / commercial products (Fluree Core, Fluree Solo, enterprise
  support) have separate terms obtained directly from
  [Fluree PBC](https://flur.ee/). Verify current licensing for your intended
  use with them.

If you fork or deploy this project you are responsible for holding valid
licenses for both dependencies according to your use case. This repository's
MIT grant does not — and cannot — sublicense them.
