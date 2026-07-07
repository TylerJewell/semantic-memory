package com.example.application.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.application.conflict.EntityResolver;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ResurrectionTest {

  private static final Instant EARLIER = Instant.parse("2024-01-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2025-01-01T00:00:00Z");

  private static EntityResolver resolver() {
    return new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of("acme corp", "Acme"));
  }

  private static Triple authoredAcme() {
    return new Triple(
        "Tyler",
        "currentEmployer",
        "Acme",
        new ProvenanceEnvelope(Layer.AUTHORED, "vault/x", EARLIER, 0.5),
        true);
  }

  private static Triple derivedAcme() {
    return new Triple(
        "Tyler",
        "currentEmployer",
        "Acme",
        new ProvenanceEnvelope(Layer.DERIVED, "corpus/y", LATER, 0.99),
        true);
  }

  // EC-026: a corpus doc re-derives a fact that was previously AUTHORED but has been tombstoned
  // (deleted from the vault). Under REQUIRE_REVIEW it must surface as a resurrection: reported,
  // flagged for review, re-entering as DERIVED — never reactivating the authored triple.
  @Test
  public void ec026_reExtractionResurfacesAsDerivedUnderReview() {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);

    // The authored fact was deleted — a tombstoned (inactive) AUTHORED triple.
    Triple tombstonedAuthored = Tombstone.tombstone(authoredAcme());
    store.add(tombstonedAuthored);

    IngestReport report = new IngestReport();
    Triple derived = derivedAcme();

    Resurrection.Outcome2 outcome =
        Resurrection.ingest(
            derived,
            store,
            List.of(tombstonedAuthored),
            resolver,
            report,
            Resurrection.Policy.REQUIRE_REVIEW);

    assertTrue(outcome.resurrection(), "re-derived tombstoned authored fact is a resurrection");
    assertTrue(outcome.requiresReview(), "REQUIRE_REVIEW flags the fact for review");

    // Every ACTIVE triple asserting this fact must be DERIVED — never restored as AUTHORED.
    List<Triple> active = store.allActive();
    boolean assertsFact = false;
    for (Triple t : active) {
      if (resolver.sameEntity(t.subject(), "Tyler")
          && t.predicate().equals("currentEmployer")
          && resolver.sameEntity(t.object(), "Acme")) {
        assertsFact = true;
        assertEquals(
            Layer.DERIVED, t.envelope().layer(), "resurrected fact re-enters as DERIVED, not AUTHORED");
      }
    }
    assertTrue(assertsFact, "the re-derived fact was added to the store");

    // The previously-tombstoned authored triple is NOT reactivated — still inactive.
    assertFalse(tombstonedAuthored.active(), "tombstoned authored triple stays inactive");
    for (Triple t : active) {
      assertFalse(
          t.envelope().layer() == Layer.AUTHORED,
          "no AUTHORED triple is active after resurrection");
    }
  }

  // Control: with NO tombstoned authored fact present, the same derived fact is a normal NEW ingest
  // and is not reported as a resurrection.
  @Test
  public void control_noTombstoneMeansNoResurrection() {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);
    IngestReport report = new IngestReport();

    Resurrection.Outcome2 outcome =
        Resurrection.ingest(
            derivedAcme(), store, List.of(), resolver, report, Resurrection.Policy.REQUIRE_REVIEW);

    assertFalse(outcome.resurrection(), "no tombstoned authored fact -> not a resurrection");
    assertFalse(outcome.requiresReview(), "nothing to review");
    assertEquals(Outcome.NEW, outcome.ingestOutcome(), "normal NEW ingest");
    assertEquals(1, report.newCount());
  }
}
