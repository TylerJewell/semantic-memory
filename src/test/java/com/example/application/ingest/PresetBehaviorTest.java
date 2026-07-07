package com.example.application.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.application.conflict.EntityResolver;
import com.example.application.policy.Policy;
import com.example.application.policy.PolicyParser;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * EC-014: preset policies produce DIFFERENT ingest outcomes on the SAME fixture — behavior is
 * data-driven by policy.md, not hard-coded.
 */
public class PresetBehaviorTest {

  private static final Instant EARLIER = Instant.parse("2024-01-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2025-01-01T00:00:00Z");

  private static Policy loadPreset(String name) throws IOException {
    String path = "policies/" + name + ".md";
    try (InputStream in =
        PresetBehaviorTest.class.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(in, "preset should exist on classpath: " + path);
      String md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return PolicyParser.parse(md);
    }
  }

  private static EntityResolver resolver() {
    return new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of());
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

  private static boolean contains(List<Triple> triples, String o, Layer layer) {
    return triples.stream()
        .anyMatch(t -> t.object().equals(o) && t.envelope().layer() == layer);
  }

  /** Run the SAME fixture (authored Acme, ingest derived Globex) under one preset. */
  private static Outcome ingestUnder(Policy policy, boolean[] derivedPersisted) {
    EntityResolver resolver = resolver();
    TripleStore store = new InMemoryTripleStore(resolver);
    store.add(authored("Tyler", "currentEmployer", "Acme"));
    IngestReport report = new IngestReport();

    Triple d = derived("Tyler", "currentEmployer", "Globex");
    Outcome outcome = IngestGate.ingest(d, store, resolver, report, policy);
    derivedPersisted[0] = contains(store.allActive(), "Globex", Layer.DERIVED);
    return outcome;
  }

  // EC-014: all 3 presets parse, and their on_authored_conflict directives drive divergent outcomes.
  @Test
  public void presetsDivergeOnSameFixture() throws IOException {
    Policy personal = loadPreset("personal-assistant");
    Policy compliance = loadPreset("compliance-legal");
    Policy runbook = loadPreset("eng-runbook");

    // All three parse.
    assertNotNull(personal);
    assertNotNull(compliance);
    assertNotNull(runbook);
    assertEquals("flag", personal.onAuthoredConflict());
    assertEquals("reject", compliance.onAuthoredConflict());
    assertEquals("reject", runbook.onAuthoredConflict());

    boolean[] personalPersisted = new boolean[1];
    boolean[] compliancePersisted = new boolean[1];
    boolean[] runbookPersisted = new boolean[1];

    Outcome personalOutcome = ingestUnder(personal, personalPersisted);
    Outcome complianceOutcome = ingestUnder(compliance, compliancePersisted);
    Outcome runbookOutcome = ingestUnder(runbook, runbookPersisted);

    // personal-assistant: flag -> CONFLICTING, derived persists (both values live).
    assertEquals(Outcome.CONFLICTING, personalOutcome);
    assertTrue(personalPersisted[0], "flag posture persists the conflicting derived triple");

    // compliance-legal: reject -> REJECTED, derived NOT persisted.
    assertEquals(Outcome.REJECTED, complianceOutcome);
    assertFalse(compliancePersisted[0], "reject posture does not persist the derived triple");

    // eng-runbook: reject -> REJECTED, derived NOT persisted.
    assertEquals(Outcome.REJECTED, runbookOutcome);
    assertFalse(runbookPersisted[0]);

    // The core claim: same input, DIFFERENT outcome across presets.
    assertNotEquals(
        personalOutcome, complianceOutcome, "presets must diverge on the same fixture");
    assertNotEquals(personalPersisted[0], compliancePersisted[0], "store state must diverge too");
  }
}
