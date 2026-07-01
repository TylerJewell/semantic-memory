# semantic-memory

An AI-memory system — the durable, queryable knowledge base an agent uses to remember facts across sessions and conversations — built on **Akka SDK 3.6** for the control plane and **Fluree** for the unified graph + vector + provenance store.

Positioned against [Cognee](https://github.com/topoteretes/cognee) (a well-known Python AI-memory framework), this project reproduces the same four-verb API (`remember` / `recall` / `forget` / `improve`) and the same graph + vector retrieval strategies, but with a materially smaller footprint and stronger durability guarantees.

## What it does

- **Remember** a fact — extract entities and relationships with an LLM, embed the text, store it all in Fluree as an immutable, cryptographically-committed RDF transaction.
- **Recall** — pick one of five retrieval strategies (`CHUNKS`, `RAG`, `GRAPH`, `HYBRID`, or `FEELING_LUCKY`), or run all of them side-by-side, and answer questions from the accumulated memory.
- **Forget** — clear the ledger.
- **Compare** — the built-in "compare all strategies" mode makes the architecture difference between retrievers visible on any question.

## Benchmark (HotpotQA distractor split, n=10)

| Strategy | Exact Match | Token F1 | Mean latency |
| --- | --- | --- | --- |
| CHUNKS (no LLM) | 0.00 | 0.01 | 2.3 s |
| RAG | 0.60 | 0.74 | 4.5 s |
| GRAPH | 0.70 | 0.84 | 4.4 s |
| **HYBRID** | **0.70** | **0.88** | **4.6 s** |
| FEELING_LUCKY | 0.70 | 0.84 | 6.8 s |

Real multi-hop reasoning over the actual HotpotQA dev-set distractor paragraphs, scored with the official HotpotQA F1 + EM. For comparison, Cognee reports **F1 = 0.79 on BEAM at 100K tokens** in their README — this project sits in the same performance band.

## Footprint

- **2 processes** — Akka service + Fluree binary
- **~550 MB** resident memory total
- **~5 s** cold start
- **1,066 lines** of Java in 19 files, plus 198 lines of tests

For reference, the Cognee Python codebase is ~120,000 lines across 1,164 files.

## Prerequisites

- Java 21+ and Maven 3.9+
- Fluree v4.1.1+ binary (download from [fluree/db releases](https://github.com/fluree/db/releases))
- A Google Gemini API key (`GOOGLE_AI_GEMINI_API_KEY` environment variable)
- The Akka SDK context docs (fetched via `akka specify init .` after installing the [Akka CLI](https://doc.akka.io/operations/cli/installation.html))

## Quick start

```bash
# 1. Start Fluree
./fluree.exe init
./fluree.exe server start --listen-addr 127.0.0.1:8090
./fluree.exe create memory

# 2. Set your API key
export GOOGLE_AI_GEMINI_API_KEY=...

# 3. Compile and run
mvn compile exec:java

# 4. Open the UI
open http://localhost:9000/
```

Two tabs:
- **App** — remember / recall / compare, with a live memory-counter, drag-drop `.txt`/`.md` import, and 5 selectable retrieval strategies.
- **Architecture** — side-by-side comparison of Cognee vs. this project with measured footprint numbers.

## HTTP API

| Method + path | Body | Returns |
| --- | --- | --- |
| `POST /api/remember` | `{text}` | `{graph, commit}` |
| `POST /api/recall` | `{question, strategy?}` | `{answer, context, strategy}` |
| `POST /api/compare` | `{question}` | `{question, results[]}` |
| `POST /api/forget` | `{}` | `{status}` |
| `GET  /api/stats` | — | `{chunks, entities, commits}` |
| `GET  /api/recent` | — | `{chunks[]}` |

## Evaluation

Run the HotpotQA harness against your local service:

```bash
python -X utf8 scripts/eval_adapter.py --items 10
```

Fetches 10 HotpotQA dev-set distractor items, ingests all 100 paragraphs (gold + distractors), asks the multi-hop question of each retrieval strategy, and scores with token-level F1 and exact match.

## Architecture

Everything the LLM does (entity extraction, structured output, question answering) runs on **Google Gemini 2.5 Flash** through Akka's built-in LangChain4j integration — no separate `instructor` / `LiteLLM` layer needed. Structured outputs use `responseConformsTo(SomeRecord.class)`.

The `RememberWorkflow` is an Akka SDK `Workflow` — meaning a crashed pipeline resumes at the failed step rather than restarting from scratch. That's the durability property Cognee's Python "best-effort rollback ledger" only approximates.

Fluree stores everything in one place:
- **Graph** — RDF triples queried with SPARQL 1.1
- **Vector** — cosine-similarity over stored `@vector` embeddings (Gemini `gemini-embedding-001`, 768 dims)
- **Provenance** — every write returns a cryptographic commit hash; time-travel queries reconstruct any prior state

## License

MIT. See `LICENSE`.

## Credit

Cognee (topoteretes) did substantial prior work on the AI-memory problem, and their eval framework's benchmark adapters were the reference for this project's evaluation approach. Where their framework needs 5+ processes and 120K lines of Python, this project needs 2 processes and 1K lines of Java — but that is because it stands on the specific shoulders of Akka's durable workflow model and Fluree's unified graph+vector store, not because their work was wrong.
