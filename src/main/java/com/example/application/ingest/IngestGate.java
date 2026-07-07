package com.example.application.ingest;

import com.example.application.conflict.EntityResolver;
import com.example.application.policy.Policy;
import com.example.domain.Triple;
import java.util.List;

/**
 * Model-1 ingest gate: classify a DERIVED triple against the store and apply side effects. A
 * contradiction is NEVER blocked — conflicting derived triples are still persisted so the resolution
 * cascade can serve the authored value and flag the derived one.
 */
public final class IngestGate {

  private IngestGate() {}

  /**
   * Classify {@code derived} against {@code store} and apply Model-1 side effects. Updates {@code
   * report} and returns the {@link Outcome}.
   */
  public static Outcome ingest(
      Triple derived, TripleStore store, EntityResolver resolver, IngestReport report) {
    Outcome outcome = classifyAndApply(derived, store, resolver);
    report.add(outcome);
    return outcome;
  }

  /**
   * Policy-aware variant of {@link #ingest(Triple, TripleStore, EntityResolver, IngestReport)}.
   * Identical for NEW / CORROBORATING / SUPPRESSED, but the CONFLICTING branch honors {@code
   * policy.onAuthoredConflict()}: {@code "flag"} (default) persists the derived triple and reports
   * CONFLICTING; {@code "reject"} does NOT persist it and returns {@link Outcome#REJECTED}.
   */
  public static Outcome ingest(
      Triple derived,
      TripleStore store,
      EntityResolver resolver,
      IngestReport report,
      Policy policy) {
    Outcome outcome = classifyAndApply(derived, store, resolver, policy);
    report.add(outcome);
    return outcome;
  }

  private static Outcome classifyAndApply(
      Triple derived, TripleStore store, EntityResolver resolver) {
    return classifyAndApply(derived, store, resolver, null);
  }

  private static Outcome classifyAndApply(
      Triple derived, TripleStore store, EntityResolver resolver, Policy policy) {
    // 1. An authored negation wins — suppress without adding.
    if (store.hasActiveNegation(derived.subject(), derived.predicate(), derived.object())) {
      return Outcome.SUPPRESSED;
    }

    // 2. Compare against active authored triples for this subject+predicate.
    List<Triple> authored = store.authoredMatching(derived.subject(), derived.predicate());
    if (authored.isEmpty()) {
      store.add(derived);
      return Outcome.NEW;
    }

    for (Triple a : authored) {
      if (resolver.sameEntity(a.object(), derived.object())) {
        // Same fact (alias-aware) — corroborate, do not add a duplicate.
        store.recordCorroboration(derived, a);
        return Outcome.CORROBORATING;
      }
    }

    // Authored value(s) exist but none match — a real conflict.
    if (policy != null && "reject".equals(policy.onAuthoredConflict())) {
      // Reject posture: do NOT persist the derived triple.
      return Outcome.REJECTED;
    }
    // Flag posture (default): persist BOTH so the resolution cascade can serve/flag them.
    store.add(derived);
    return Outcome.CONFLICTING;
  }
}
