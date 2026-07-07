package com.example.application.embeddings;

/**
 * Guards vector operations against cross-model comparison (EC-004). A store is tagged with the
 * embedding {@code modelId} that produced its vectors; vectors are not comparable across models.
 * Pure logic — no Fluree/HTTP/LLM/Akka.
 */
public final class ModelIdGuard {

  private ModelIdGuard() {}

  /**
   * Refuse vector ops when the store's tagged model differs from the active model. An untagged store
   * ({@code storedModelId == null}) is permitted.
   */
  public static void check(String storedModelId, String activeModelId) {
    if (storedModelId != null && !storedModelId.equals(activeModelId)) {
      throw new IllegalStateException(
          "embedding model mismatch: store was built with '"
              + storedModelId
              + "' but active model is '"
              + activeModelId
              + "'; vectors are not comparable across models");
    }
  }

  /** True when vector ops are allowed: untagged store, or stored model equals active model. */
  public static boolean compatible(String storedModelId, String activeModelId) {
    return storedModelId == null || storedModelId.equals(activeModelId);
  }
}
