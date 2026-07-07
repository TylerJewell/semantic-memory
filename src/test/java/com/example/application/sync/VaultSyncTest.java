package com.example.application.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.application.conflict.EntityResolver;
import com.example.application.ingest.InMemoryTripleStore;
import com.example.application.ingest.TripleStore;
import com.example.domain.Layer;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** EC-010 (frontmatter -> AUTHORED) and EC-016 (prose never derived). */
class VaultSyncTest {

  private static TripleStore store() {
    return new InMemoryTripleStore(new EntityResolver(EntityResolver.Mode.STRICT, Map.of()));
  }

  @Test
  void ec010_frontmatterBecomesAuthoredTriple() {
    TripleStore store = store();
    Instant now = Instant.parse("2026-07-06T00:00:00Z");
    List<Triple> triples =
        VaultSync.syncVaultFile(
            "vault/people/tyler.md",
            "Tyler",
            "---\ncurrentEmployer: Acme\n---\n# Tyler prose here",
            now,
            store);

    assertEquals(1, triples.size());
    Triple t = triples.get(0);
    assertEquals("currentEmployer", t.predicate());
    assertEquals("Acme", t.object());
    assertEquals(Layer.AUTHORED, t.envelope().layer());
    assertEquals("vault/people/tyler.md", t.envelope().source());
    assertEquals(1.0, t.envelope().conf());
  }

  @Test
  void ec016_proseNeverProducesDerivedTriples() {
    TripleStore store = store();
    Instant now = Instant.parse("2026-07-06T00:00:00Z");
    List<Triple> triples =
        VaultSync.syncVaultFile(
            "vault/people/tyler.md",
            "Tyler",
            "---\ncurrentEmployer: Acme\n---\n# Tyler prose here",
            now,
            store);

    // All returned triples are AUTHORED; none DERIVED with source == the vault path.
    assertTrue(triples.stream().allMatch(t -> t.envelope().layer() == Layer.AUTHORED));
    assertFalse(
        triples.stream()
            .anyMatch(
                t ->
                    t.envelope().layer() == Layer.DERIVED
                        && "vault/people/tyler.md".equals(t.envelope().source())));
    // Nothing in the store is derived from the vault path either.
    assertFalse(
        store.allActive().stream()
            .anyMatch(
                t ->
                    t.envelope().layer() == Layer.DERIVED
                        && "vault/people/tyler.md".equals(t.envelope().source())));
  }
}
