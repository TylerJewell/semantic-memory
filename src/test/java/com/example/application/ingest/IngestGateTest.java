package com.example.application.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

public class IngestGateTest {

  private static final Instant EARLIER = Instant.parse("2024-01-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2025-01-01T00:00:00Z");

  private static EntityResolver resolver() {
    return new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of("acme corp", "Acme"));
  }

  private static Triple authored(String subject, String predicate, String object) {
    return new Triple(
        subject,
        predicate,
        object,
        new ProvenanceEnvelope(Layer.AUTHORED, "vault/x", EARLIER, 0.5),
        true);
  }

  private static Triple derived(String subject, String predicate, String object) {
    return new Triple(
        subject,
        predicate,
        object,
        new ProvenanceEnvelope(Layer.DERIVED, "corpus/y", LATER, 0.99),
        true);
  }

  private static boolean contains(List<Triple> triples, String s, String p, String o, Layer layer) {
    return triples.stream()
        .anyMatch(
            t ->
                t.subject().equals(s)
                    && t.predicate().equals(p)
                    && t.object().equals(o)
                    && t.envelope().layer() == layer);
  }

  // EC-022: empty store -> a fresh derived fact is NEW and persisted at DERIVED layer.
  @Test
  public void ec022_newFactIntoEmptyStore() {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);
    IngestReport report = new IngestReport();

    Triple d = derived("Tyler", "currentEmployer", "Globex");
    Outcome outcome = IngestGate.ingest(d, store, resolver, report);

    assertEquals(Outcome.NEW, outcome);
    assertTrue(
        contains(store.allActive(), "Tyler", "currentEmployer", "Globex", Layer.DERIVED),
        "derived triple should be persisted at DERIVED layer");
    assertEquals(1, report.newCount());
  }

  // EC-021: an alias-equivalent derived fact corroborates the authored one — no duplicate added.
  @Test
  public void ec021_corroboratingNoDuplicate() {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);
    store.add(authored("Tyler", "currentEmployer", "Acme"));
    int before = store.allActive().size();
    IngestReport report = new IngestReport();

    Triple d = derived("Tyler", "currentEmployer", "Acme Corp");
    Outcome outcome = IngestGate.ingest(d, store, resolver, report);

    assertEquals(Outcome.CORROBORATING, outcome);
    assertEquals(before, store.allActive().size(), "no duplicate triple should be added");
    assertEquals(1, store.corroborationCount());
    assertEquals(1, report.corroborating());
  }

  // EC-020: a conflicting derived fact is NEVER blocked — both persist; cascade serves AUTHORED.
  @Test
  public void ec020_conflictingNeverBlocked() {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);
    Triple authoredAcme = authored("Tyler", "currentEmployer", "Acme");
    store.add(authoredAcme);
    IngestReport report = new IngestReport();

    Triple derivedGlobex = derived("Tyler", "currentEmployer", "Globex");
    Outcome outcome = IngestGate.ingest(derivedGlobex, store, resolver, report);

    assertEquals(Outcome.CONFLICTING, outcome);
    assertTrue(
        contains(store.allActive(), "Tyler", "currentEmployer", "Acme", Layer.AUTHORED),
        "authored value stays active");
    assertTrue(
        contains(store.allActive(), "Tyler", "currentEmployer", "Globex", Layer.DERIVED),
        "conflicting derived value is also persisted (never blocked)");
    assertEquals(1, report.conflicting());

    // Cascade serves the AUTHORED value; the derived one is flagged.
    Resolution resolution =
        ResolutionCascade.resolve(
            List.of(authoredAcme, derivedGlobex), List.of(List.of("vault/"), List.of("corpus/")));
    Resolution.Resolved resolved = assertInstanceOf(Resolution.Resolved.class, resolution);
    assertEquals("Acme", resolved.winner().object());
    assertEquals(Layer.AUTHORED, resolved.winner().envelope().layer());
    assertFalse(resolved.flagged().isEmpty(), "derived value should be flagged");
    assertEquals("Globex", resolved.flagged().get(0).object());
  }
}
