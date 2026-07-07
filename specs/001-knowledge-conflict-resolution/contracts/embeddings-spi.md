# Contract — Embeddings SPI + model selection (Workstream A)

> **Tier:** authoritative (specs/)
> **Purpose:** Specifies the `Embeddings` interface and model-selection contract for dev vs. prod runs.
> **Audience:** Implementers of the embeddings layer.
> **Lifecycle:** durable

Refactors the static `application/GeminiEmbeddings.java` behind an interface so dev runs
in-process and prod uses Gemini, selected by env var with no code change.

## `Embeddings` interface (`application/embeddings/Embeddings.java`)

```java
public interface Embeddings {
  /** Meaning-vector for text. Dimensionality MUST equal dimensions(). */
  double[] embed(String text);

  /** Identity of the embedding model, e.g. "gemini-embedding-001" or "all-mpnet-base-v2". */
  String modelId();

  /** Vector dimensionality (768 for both default impls). */
  int dimensions();
}
```

**Implementations**
- `GeminiEmbeddings` — existing HTTP call, moved behind the interface; `modelId =
  "gemini-embedding-001"`, `dimensions = 768`.
- `LocalOnnxEmbeddings` — LangChain4j in-JVM ONNX `all-mpnet-base-v2`; `dimensions = 768`
  (schema-preserving, R2). No network at inference.

## Selection contract (FR-A1, `application/model/ModelSelector.java`)

| Condition | Embeddings | LLM (ModelProvider) |
|---|---|---|
| `GOOGLE_AI_GEMINI_API_KEY` **absent** | `LocalOnnxEmbeddings` | in-JVM (Jlama, pure-Java — no daemon) |
| `GOOGLE_AI_GEMINI_API_KEY` **present** | `GeminiEmbeddings` | Gemini (unchanged) |

- Selection happens once at startup; the active `modelId` is logged (FR-A2 no inference-time
  network in local mode).
- The three Agents (`GraphExtractorAgent`, `QaAgent`, `SearchTypeClassifierAgent`) resolve their
  model via Akka `ModelProvider` — **do not bypass the SDK** (FR-A4). Verify provider config keys
  against `akka-context/sdk/model-provider-details.html.md` before wiring.

## Store model-identity tag (FR-A5)

- On first write, the Fluree store records its embedding `modelId`.
- On startup / write, if the active `modelId` ≠ the store's recorded tag → **warn and refuse**
  vector writes/queries (vectors are not cross-model comparable). Switching models requires
  re-embedding the corpus. Dev (`all-mpnet-base-v2`) and prod (`gemini-embedding-001`) stores are
  **not interchangeable** even though both are 768-dim.

## Structured-output resilience (FR-A6)

`GraphExtractorAgent` and `SearchTypeClassifierAgent` use `responseConformsTo(...)`. On small
local models:
- add a **repair loop** — on schema-validation failure, re-prompt echoing the violation (bounded
  retries);
- provide a **reduced-schema fallback** for dev;
- document that dev-mode graph quality is lower. `QaAgent` (free text) is unaffected.
