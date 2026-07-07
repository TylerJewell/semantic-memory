# Quickstart — Knowledge Conflict Resolution

> **Tier:** authoritative (specs/)
> **Purpose:** Runnable acceptance walk-throughs, one per delivery phase, proving each phase's exit criteria.
> **Audience:** Implementers and testers validating the feature end-to-end.
> **Lifecycle:** durable

Three flows, one per delivery phase. Each is an acceptance walk-through you can run to prove the
phase's exit criteria.

## Prerequisites
- JDK 21 (with `--add-modules jdk.incubator.vector`), Maven, the Akka CLI (`/akka:setup` if missing).
- Local Fluree HTTP server on `127.0.0.1:8090` (as today).
- For local-model dev: no external service — Jlama runs the LLM in-JVM; weights download once at setup.
- **No** `GOOGLE_AI_GEMINI_API_KEY` for local mode; set it for prod/Gemini mode.

## Flow A — Run locally, zero external accounts *(Phase A / SC-001)*

```bash
unset GOOGLE_AI_GEMINI_API_KEY          # ensure local mode
/akka:build                              # build + start service (port from application.conf)
# service logs: "Embeddings: all-mpnet-base-v2 (local)  |  LLM: jlama (in-JVM)"

curl -sX POST localhost:PORT/api/remember \
  -H 'content-type: application/json' \
  -d '{"text":"Akka provides resilience for distributed systems."}'
# -> {graph:{...}, commit:"..."}  with NO outbound network at inference

curl -sX POST localhost:PORT/api/recall \
  -H 'content-type: application/json' \
  -d '{"question":"What does Akka provide?","strategy":"HYBRID"}'
```
**Pass**: both calls succeed with the key unset. Set the key, restart → logs show Gemini, same
calls work unchanged.

## Flow B — Vault vs corpus, kept apart *(Phase B+F / SC-002, SC-006)*

```bash
mkdir -p vault/people corpus/notes
printf -- '---\ncurrentEmployer: Acme\n---\n# Tyler\n' > vault/people/tyler.md
printf 'Tyler now works at Acme Corp per the offer letter.\n'  > corpus/notes/offer.md

curl -sX POST localhost:PORT/api/sync
# report: {"total":.., "new":.., "corroborating":1, "conflicting":0, ...}
# (corpus "Acme Corp" resolves to authored "Acme" via alias-aware -> corroborating, no duplicate)
```
**Pass**: `/api/sync` returns a report; the authored triple carries `{layer:authored,
source:vault/people/tyler.md}` and the corpus match is corroborating (no duplicate). A file
placed under a path claimed by both globs → `409 overlap`.

Swap `policy.md`'s `on_authored_match = corroborate` presets and re-sync to see behavior change
(SC-006).

## Flow C — A contradiction is surfaced, never resolved *(Phase C+D+E / SC-003..005, 007)*

```bash
printf 'Tyler joined Globex last month.\n' > corpus/notes/globex.md
curl -sX POST localhost:PORT/api/sync
# report: {"conflicting":1, ...}  facts[].outcome == "conflicting", contestedWith "ex:acme"

curl -s localhost:PORT/api/conflicts
# -> one ContestedState: candidates [Acme(authored,1.0), Globex(derived,0.7)], read "freeze-incumbent"

curl -sX POST localhost:PORT/api/recall \
  -d '{"question":"Where does Tyler work?","strategy":"GRAPH"}'
# -> answer built on the FROZEN incumbent "Acme", tagged contested — never silently flipped
```
**Pass**:
- Both triples persist; the conflict is flagged + reported, **never blocked** (SC-003).
- An exact match produces 0 duplicates (SC-004).
- Two conflicting *authored* facts with no discriminator → terminal `flag`, not recency (SC-005).
- Delete `currentEmployer` from `vault/people/tyler.md`, re-sync → the authored fact stops
  asserting; the corpus `Acme` re-derivation is surfaced under `re_extraction`, never silently
  restored as authored (SC-007).

## Where to look
- Policy: `policy.md` at repo root (presets in `specs/001-.../contracts/policy-md.md`).
- API: `contracts/http-api.md`. Model selection: `contracts/embeddings-spi.md`.
- Data model: `data-model.md`. Decisions: `research.md`.
