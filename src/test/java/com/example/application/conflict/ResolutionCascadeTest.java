package com.example.application.conflict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ResolutionCascadeTest {

  private static final Instant EARLIER = Instant.parse("2024-01-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2025-01-01T00:00:00Z");

  private static Triple triple(
      String subject,
      String predicate,
      String object,
      Layer layer,
      String source,
      Instant asserted,
      double conf) {
    return new Triple(
        subject,
        predicate,
        object,
        new ProvenanceEnvelope(layer, source, asserted, conf),
        true);
  }

  // EC-020: AUTHORED beats DERIVED via layer-precedence; the DERIVED loser is flagged.
  @Test
  public void ec020_layerPrecedenceFlagsCrossLayerLoser() {
    Triple authored = triple("s", "ceo", "Acme", Layer.AUTHORED, "vault/x", EARLIER, 0.5);
    Triple derived = triple("s", "ceo", "Globex", Layer.DERIVED, "corpus/y", LATER, 0.99);

    Resolution r = ResolutionCascade.resolve(List.of(authored, derived), List.of());

    Resolution.Resolved res = assertInstanceOf(Resolution.Resolved.class, r);
    assertEquals("Acme", res.winner().object());
    assertEquals("layer-precedence", res.resolvedBy());
    assertTrue(res.flagged().contains(derived));
  }

  // EC-031: layer wins even when the DERIVED candidate is more recent.
  @Test
  public void ec031_layerBeatsNewerRecency() {
    Triple authored = triple("s", "ceo", "Acme", Layer.AUTHORED, "vault/x", EARLIER, 0.5);
    Triple derived = triple("s", "ceo", "Globex", Layer.DERIVED, "corpus/y", LATER, 0.99);

    Resolution r = ResolutionCascade.resolve(List.of(authored, derived), List.of());

    Resolution.Resolved res = assertInstanceOf(Resolution.Resolved.class, r);
    assertEquals("Acme", res.winner().object());
  }

  // EC-032: two AUTHORED triples broken by source-priority buckets.
  @Test
  public void ec032_authoredTieBrokenBySourcePriority() {
    Triple a = triple("s", "ceo", "Acme", Layer.AUTHORED, "vault/people/a", EARLIER, 0.5);
    Triple b = triple("s", "ceo", "Globex", Layer.AUTHORED, "vault/imports/b", LATER, 0.5);

    Resolution r =
        ResolutionCascade.resolve(
            List.of(a, b), List.of(List.of("vault/people/"), List.of("vault/imports/")));

    Resolution.Resolved res = assertInstanceOf(Resolution.Resolved.class, r);
    assertEquals("Acme", res.winner().object());
    assertEquals("source-priority", res.resolvedBy());
  }

  // EC-033: DERIVED top set, confidence beats newer recency.
  @Test
  public void ec033_derivedConfidenceBeatsRecency() {
    Triple a = triple("s", "ceo", "Acme", Layer.DERIVED, "corpus/x", EARLIER, 0.9);
    Triple b = triple("s", "ceo", "Globex", Layer.DERIVED, "corpus/x", LATER, 0.6);

    Resolution r = ResolutionCascade.resolve(List.of(a, b), List.of(List.of("corpus/")));

    Resolution.Resolved res = assertInstanceOf(Resolution.Resolved.class, r);
    assertEquals("Acme", res.winner().object());
    assertEquals("confidence", res.resolvedBy());
  }

  // EC-029: DERIVED top set, equal confidence -> recency breaks the tie.
  @Test
  public void ec029_derivedRecencyBreaksEqualConfidence() {
    Triple a = triple("s", "ceo", "Acme", Layer.DERIVED, "corpus/x", EARLIER, 0.7);
    Triple b = triple("s", "ceo", "Globex", Layer.DERIVED, "corpus/x", LATER, 0.7);

    Resolution r = ResolutionCascade.resolve(List.of(a, b), List.of(List.of("corpus/")));

    Resolution.Resolved res = assertInstanceOf(Resolution.Resolved.class, r);
    assertEquals("Globex", res.winner().object());
    assertEquals("recency", res.resolvedBy());
  }

  // EC-023: AUTHORED tie in the same source bucket -> Contested, recency NOT used.
  @Test
  public void ec023_authoredTieContestedNotBrokenByRecency() {
    Triple a = triple("s", "ceo", "Acme", Layer.AUTHORED, "vault/z", EARLIER, 0.5);
    Triple b = triple("s", "ceo", "Globex", Layer.AUTHORED, "vault/z", LATER, 0.5);

    Resolution r = ResolutionCascade.resolve(List.of(a, b), List.of(List.of("vault/")));

    Resolution.Contested res = assertInstanceOf(Resolution.Contested.class, r);
    assertEquals("authored-tie", res.reason());
    assertTrue(res.candidates().contains(a));
    assertTrue(res.candidates().contains(b));
  }
}
