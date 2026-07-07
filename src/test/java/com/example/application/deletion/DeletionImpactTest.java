package com.example.application.deletion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.application.conflict.EntityResolver;
import com.example.application.deletion.DeletionImpact.Category;
import com.example.application.deletion.DeletionImpact.Decision;
import com.example.application.deletion.DeletionImpact.ImpactSet;
import com.example.application.deletion.DeletionImpact.Impacted;
import com.example.application.ingest.InMemoryTripleStore;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Maps EC-026: deletion-impact scan is read-only and categorizes questioned facts. */
public class DeletionImpactTest {

  private static final Instant WHEN = Instant.parse("2025-01-01T00:00:00Z");

  private static EntityResolver resolver() {
    return new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of());
  }

  private static Triple authored(String s, String p, String o) {
    return new Triple(s, p, o, new ProvenanceEnvelope(Layer.AUTHORED, "vault/x", WHEN, 0.9), true);
  }

  private static Triple derived(String s, String p, String o) {
    return new Triple(s, p, o, new ProvenanceEnvelope(Layer.DERIVED, "corpus/y", WHEN, 0.8), true);
  }

  @Test
  void scanCategorizesImpactAndIsReadOnly() {
    EntityResolver resolver = resolver();
    InMemoryTripleStore store = new InMemoryTripleStore(resolver);

    Triple f = authored("Tyler", "worksAt", "Acme"); // fact to be deleted
    Triple d = derived("Tyler", "worksInState", "Oregon"); // lineage dependent
    Triple r = derived("Tyler", "worksAt", "Globex"); // flagged rival
    Triple ra = derived("Tyler", "worksAt", "Acme"); // re-assertion

    store.add(f);
    store.add(d);
    store.add(r);
    store.add(ra);

    Map<Triple, List<Triple>> lineage = Map.of(d, List.of(f));

    int before = store.allActive().size();
    ImpactSet impact = DeletionImpact.scan(f, store, lineage, resolver);
    int after = store.allActive().size();

    // READ-ONLY: scan mutated nothing.
    assertEquals(before, after, "scan must not mutate the store");

    List<Impacted> lineageDep = impact.byCategory(Category.LINEAGE_DEPENDENT);
    List<Impacted> rivals = impact.byCategory(Category.FLAGGED_RIVAL);
    List<Impacted> reassertions = impact.byCategory(Category.REASSERTION);

    assertTrue(lineageDep.stream().anyMatch(i -> i.fact().equals(d)), "D is lineage dependent");
    assertTrue(rivals.stream().anyMatch(i -> i.fact().equals(r)), "R is a flagged rival");
    assertTrue(reassertions.stream().anyMatch(i -> i.fact().equals(ra)), "RA is a re-assertion");

    // Does NOT contain F itself.
    assertFalse(
        impact.items().stream().anyMatch(i -> i.fact().equals(f)),
        "the deleted fact must not appear in its own impact set");

    // Every item is PENDING with a non-blank reason.
    for (Impacted i : impact.items()) {
      assertEquals(Decision.PENDING, i.decision(), "every scanned item is PENDING");
      assertFalse(i.reason() == null || i.reason().isBlank(), "every item has a reason");
    }

    // decide() returns a copy without mutating the store.
    Impacted rival = rivals.get(0);
    int beforeDecide = store.allActive().size();
    Impacted decided = DeletionImpact.decide(rival, Decision.REMOVE);
    assertEquals(Decision.REMOVE, decided.decision());
    assertEquals(rival.fact(), decided.fact());
    assertEquals(Decision.PENDING, rival.decision(), "original item unchanged");
    assertEquals(beforeDecide, store.allActive().size(), "decide must not mutate the store");
  }
}
