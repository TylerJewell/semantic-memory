package com.example.application.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.example.application.conflict.EntityResolver;
import com.example.application.conflict.Resolution;
import com.example.application.conflict.ResolutionCascade;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DeletionTest {

  private static final Instant EARLIER = Instant.parse("2024-01-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2025-01-01T00:00:00Z");

  private static EntityResolver resolver() {
    return new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of("acme corp", "Acme"));
  }

  // EC-025: a tombstoned (inactive) authored triple no longer participates — the incoming
  // derived fact is NEW (no conflict), and the cascade ignores the tombstone.
  @Test
  public void ec025_tombstonedAuthoredDoesNotConflict() {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);

    Triple authoredAcme =
        new Triple(
            "Tyler",
            "currentEmployer",
            "Acme",
            new ProvenanceEnvelope(Layer.AUTHORED, "vault/x", EARLIER, 0.5),
            true);
    Triple tombstonedAcme = Tombstone.tombstone(authoredAcme);
    store.add(tombstonedAcme);

    IngestReport report = new IngestReport();
    Triple derivedGlobex =
        new Triple(
            "Tyler",
            "currentEmployer",
            "Globex",
            new ProvenanceEnvelope(Layer.DERIVED, "corpus/y", LATER, 0.99),
            true);
    Outcome outcome = IngestGate.ingest(derivedGlobex, store, resolver, report);

    // The inactive authored value is not returned by authoredMatching -> NEW, not CONFLICTING.
    assertEquals(Outcome.NEW, outcome);
    assertEquals(1, report.newCount());

    // The cascade over [tombstone, active] resolves to the single active triple.
    Resolution resolution =
        ResolutionCascade.resolve(
            List.of(tombstonedAcme, derivedGlobex),
            List.of(List.of("vault/"), List.of("corpus/")));
    Resolution.Resolved resolved = assertInstanceOf(Resolution.Resolved.class, resolution);
    assertEquals("Globex", resolved.winner().object(), "only the active triple participates");
  }
}
