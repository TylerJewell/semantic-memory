package com.example.application.ingest;

import com.example.application.conflict.EntityResolver;
import com.example.domain.Layer;
import com.example.domain.Triple;
import java.util.List;

/**
 * Resurrection-via-re-extraction guard (design §6, EC-026). Deleting (tombstoning) an AUTHORED fact
 * does not stop a corpus document from re-deriving it. When a corpus doc re-asserts a fact that was
 * previously AUTHORED but has since been tombstoned, the system must SURFACE it as a resurrection:
 * the fact re-enters as DERIVED (never silently restored as AUTHORED) and is reported.
 *
 * <p>Pure logic — no Fluree/HTTP/LLM/Akka. The {@link TripleStore} interface exposes only ACTIVE
 * triples, so the (inactive) tombstoned authored facts are supplied explicitly by the caller rather
 * than by modifying the store.
 */
public final class Resurrection {

  private Resurrection() {}

  /** Re-extraction policy governing how a resurrected fact is handled. */
  public enum Policy {
    OVERWRITE,
    APPEND_VERSIONED,
    DIFF_ADD_ONLY,
    REQUIRE_REVIEW
  }

  /**
   * @param ingestOutcome the underlying {@link IngestGate} classification of the re-derived fact.
   * @param resurrection true when the fact matches a tombstoned AUTHORED triple (alias-aware).
   * @param requiresReview true when a resurrection is held for review ({@code REQUIRE_REVIEW}).
   */
  public record Outcome2(Outcome ingestOutcome, boolean resurrection, boolean requiresReview) {}

  /**
   * Convenience form with no known tombstones — behaves as a normal policy-aware {@link
   * IngestGate#ingest} and never reports a resurrection.
   */
  public static Outcome2 ingest(
      Triple derived,
      TripleStore store,
      EntityResolver resolver,
      IngestReport report,
      Policy policy) {
    return ingest(derived, store, List.of(), resolver, report, policy);
  }

  /**
   * Detect resurrection against the supplied {@code tombstonedAuthored} facts and ingest the
   * re-derived fact.
   *
   * <p>A resurrection is when {@code tombstonedAuthored} contains an inactive AUTHORED triple whose
   * subject+predicate+object match {@code derived} (alias-aware). In every case the fact re-enters
   * through the normal {@link IngestGate} — it is added as DERIVED (never AUTHORED) — and the
   * previously-tombstoned authored triple is left untouched (still inactive).
   */
  public static Outcome2 ingest(
      Triple derived,
      TripleStore store,
      List<Triple> tombstonedAuthored,
      EntityResolver resolver,
      IngestReport report,
      Policy policy) {
    boolean resurrection = matchesTombstonedAuthored(derived, tombstonedAuthored, resolver);

    // The fact re-enters via the normal gate. authoredMatching returns only ACTIVE authored
    // triples, so a tombstoned authored value never matches and the derived fact classifies as NEW,
    // persisting with its own DERIVED layer — never AUTHORED, never reactivating the tombstone.
    Outcome ingestOutcome = IngestGate.ingest(derived, store, resolver, report);

    boolean requiresReview = resurrection && policy == Policy.REQUIRE_REVIEW;
    return new Outcome2(ingestOutcome, resurrection, requiresReview);
  }

  private static boolean matchesTombstonedAuthored(
      Triple derived, List<Triple> tombstonedAuthored, EntityResolver resolver) {
    for (Triple t : tombstonedAuthored) {
      if (!t.active()
          && t.envelope().layer() == Layer.AUTHORED
          && resolver.sameEntity(t.subject(), derived.subject())
          && t.predicate().equals(derived.predicate())
          && resolver.sameEntity(t.object(), derived.object())) {
        return true;
      }
    }
    return false;
  }
}
