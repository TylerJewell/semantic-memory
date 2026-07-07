package com.example.application.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.application.conflict.EntityResolver;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class NegationTest {

  private static final Instant LATER = Instant.parse("2025-01-01T00:00:00Z");

  private static EntityResolver resolver() {
    return new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of("acme corp", "Acme"));
  }

  // EC-027: an authored negation NOT(s p o) suppresses a matching derived assertion.
  @Test
  public void ec027_negationSuppressesDerived() {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);
    store.addNegation("Tyler", "currentEmployer", "Acme");
    IngestReport report = new IngestReport();

    Triple derived =
        new Triple(
            "Tyler",
            "currentEmployer",
            "Acme",
            new ProvenanceEnvelope(Layer.DERIVED, "corpus/y", LATER, 0.99),
            true);
    Outcome outcome = IngestGate.ingest(derived, store, resolver, report);

    assertEquals(Outcome.SUPPRESSED, outcome);
    assertFalse(
        store.allActive().stream().anyMatch(t -> t.object().equals("Acme")),
        "suppressed derived triple must not be added");
    assertEquals(1, report.suppressed());
  }
}
