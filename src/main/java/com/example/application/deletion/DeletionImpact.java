package com.example.application.deletion;

import com.example.application.conflict.EntityResolver;
import com.example.application.ingest.TripleStore;
import com.example.domain.Layer;
import com.example.domain.Triple;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deletion-impact scan (design R4, EC-026). When a fact F is deleted, this READ-ONLY scan surfaces
 * every active fact that should now be re-questioned so the user can adjudicate each one. The scan
 * changes nothing; applying a {@link Decision} is a separate concern.
 */
public final class DeletionImpact {

  public enum Category {
    LINEAGE_DEPENDENT,
    FLAGGED_RIVAL,
    REASSERTION
  }

  public enum Decision {
    PENDING,
    KEEP_VALID,
    ALTER,
    REMOVE
  }

  public record Impacted(Triple fact, Category category, String reason, Decision decision) {}

  public record ImpactSet(List<Impacted> items) {
    public boolean isEmpty() {
      return items.isEmpty();
    }

    public List<Impacted> byCategory(Category c) {
      return items.stream().filter(i -> i.category() == c).toList();
    }
  }

  private DeletionImpact() {}

  /**
   * READ-ONLY scan. {@code lineage} maps a derived fact to the support facts it was inferred using.
   * Excludes {@code deleted} itself from results. Every returned {@link Impacted} has decision ==
   * {@link Decision#PENDING}. Calls no mutating store method.
   */
  public static ImpactSet scan(
      Triple deleted, TripleStore store, Map<Triple, List<Triple>> lineage, EntityResolver resolver) {
    List<Impacted> out = new ArrayList<>();
    List<Triple> active = store.allActive();

    // LINEAGE_DEPENDENT — active derived facts inferred using `deleted`.
    for (Map.Entry<Triple, List<Triple>> e : lineage.entrySet()) {
      Triple derived = e.getKey();
      if (sameTriple(derived, deleted, resolver) || !isActive(derived, active, resolver)) {
        continue;
      }
      List<Triple> supports = e.getValue();
      if (supports == null) {
        continue;
      }
      boolean usedDeleted = supports.stream().anyMatch(s -> sameTriple(s, deleted, resolver));
      if (usedDeleted) {
        out.add(
            new Impacted(
                derived,
                Category.LINEAGE_DEPENDENT,
                "Derived using the deleted fact; its support was removed.",
                Decision.PENDING));
      }
    }

    // FLAGGED_RIVAL — active facts sharing subject+predicate but a different object.
    for (Triple t : active) {
      if (t == deleted || sameTriple(t, deleted, resolver)) {
        continue;
      }
      if (resolver.sameEntity(t.subject(), deleted.subject())
          && t.predicate().equals(deleted.predicate())
          && !resolver.sameEntity(t.object(), deleted.object())) {
        out.add(
            new Impacted(
                t,
                Category.FLAGGED_RIVAL,
                "Rival value outranked by the deleted fact; may now be valid.",
                Decision.PENDING));
      }
    }

    // REASSERTION — active DERIVED facts that re-assert `deleted` exactly.
    for (Triple t : active) {
      if (t == deleted) {
        continue;
      }
      if (t.envelope().layer() == Layer.DERIVED && sameTriple(t, deleted, resolver)) {
        out.add(
            new Impacted(
                t,
                Category.REASSERTION,
                "Derived corpus still re-asserts the deleted fact.",
                Decision.PENDING));
      }
    }

    return new ImpactSet(out);
  }

  /**
   * Record a user's decision on an impacted fact. Returns a new {@link Impacted}; does NOT mutate the
   * store — applying is a separate concern.
   */
  public static Impacted decide(Impacted item, Decision d) {
    return new Impacted(item.fact(), item.category(), item.reason(), d);
  }

  private static boolean sameTriple(Triple a, Triple b, EntityResolver resolver) {
    return resolver.sameEntity(a.subject(), b.subject())
        && a.predicate().equals(b.predicate())
        && resolver.sameEntity(a.object(), b.object());
  }

  private static boolean isActive(Triple t, List<Triple> active, EntityResolver resolver) {
    return active.stream().anyMatch(a -> a == t || sameTriple(a, t, resolver));
  }
}
