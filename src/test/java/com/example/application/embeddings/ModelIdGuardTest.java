package com.example.application.embeddings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** EC-004: refuse vector ops across embedding models. */
class ModelIdGuardTest {

  @Test
  void mismatchIsRefused() {
    assertThrows(
        IllegalStateException.class,
        () -> ModelIdGuard.check("gemini-embedding-001", "all-mpnet-base-v2-PLACEHOLDER"));
    assertFalse(ModelIdGuard.compatible("gemini-embedding-001", "all-mpnet-base-v2-PLACEHOLDER"));
  }

  @Test
  void sameModelIsAllowed() {
    assertDoesNotThrow(
        () ->
            ModelIdGuard.check("all-mpnet-base-v2-PLACEHOLDER", "all-mpnet-base-v2-PLACEHOLDER"));
    assertTrue(
        ModelIdGuard.compatible("all-mpnet-base-v2-PLACEHOLDER", "all-mpnet-base-v2-PLACEHOLDER"));
  }

  @Test
  void untaggedStoreIsAllowed() {
    assertDoesNotThrow(() -> ModelIdGuard.check(null, "x"));
    assertTrue(ModelIdGuard.compatible(null, "x"));
  }
}
