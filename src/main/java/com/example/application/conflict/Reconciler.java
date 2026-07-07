package com.example.application.conflict;

import com.example.application.FlureeClient;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure reconciliation over the flat list of provenance assertions Fluree returns (see {@link
 * FlureeClient#queryAllEnvelopes()}). Groups active assertions by (subject, predicate) and, for each
 * functional group that disagrees on the object, runs the {@link ResolutionCascade} to classify the
 * group as either:
 *
 * <ul>
 *   <li>a <b>disagreement</b> (Model 1): a cross-layer {@link Resolution.Resolved} where an AUTHORED
 *       winner overrides one or more flagged DERIVED losers — the authored value is served, the
 *       derived value(s) are surfaced as flagged; or
 *   <li>a <b>conflict</b>: a {@link Resolution.Contested} tie with no winner (e.g. two authored
 *       values), surfaced as candidates + reason.
 * </ul>
 *
 * <p>Stateless and side-effect free, so it is unit-testable without Fluree.
 */
public final class Reconciler {

  private Reconciler() {}

  /** A provenance envelope surfaced to a read endpoint (flagged loser or contested candidate). */
  public record FlaggedEnvelope(String object, String layer, String source, double conf) {}

  /** An AUTHORED value served over one or more flagged DERIVED losers (Model 1). */
  public record Disagreement(
      String subject,
      String predicate,
      String served,
      String servedLayer,
      List<FlaggedEnvelope> flagged) {}

  /** A no-winner tie (e.g. two authored values) with the terminal cascade reason. */
  public record Conflict(
      String subject, String predicate, List<FlaggedEnvelope> candidates, String reason) {}

  /** The reconciliation outcome: the disagreements and conflicts across the whole store. */
  public record Result(List<Disagreement> disagreements, List<Conflict> conflicts) {}

  /** Reconcile every (subject, predicate) group in the supplied assertions. */
  public static Result reconcile(List<FlureeClient.ScopedEnvelope> envelopes) {
    Map<String, List<FlureeClient.ScopedEnvelope>> groups = new LinkedHashMap<>();
    for (FlureeClient.ScopedEnvelope se : envelopes) {
      groups.computeIfAbsent(se.subject() + "|" + se.predicate(), k -> new ArrayList<>()).add(se);
    }

    List<Disagreement> disagreements = new ArrayList<>();
    List<Conflict> conflicts = new ArrayList<>();

    for (List<FlureeClient.ScopedEnvelope> members : groups.values()) {
      long distinctObjects = members.stream().map(m -> m.envelope().object()).distinct().count();
      if (distinctObjects <= 1) {
        continue; // not a disputed functional group
      }

      String subject = members.get(0).subject();
      String predicate = members.get(0).predicate();

      List<Triple> triples = new ArrayList<>();
      for (FlureeClient.ScopedEnvelope se : members) {
        triples.add(toTriple(se));
      }

      Resolution res = ResolutionCascade.resolve(triples, List.of());
      if (res instanceof Resolution.Resolved r) {
        if (!r.flagged().isEmpty() && r.winner().envelope().layer() == Layer.AUTHORED) {
          List<FlaggedEnvelope> derivedLosers = new ArrayList<>();
          for (Triple loser : r.flagged()) {
            if (loser.envelope().layer() == Layer.DERIVED) {
              derivedLosers.add(toFlagged(loser));
            }
          }
          if (!derivedLosers.isEmpty()) {
            disagreements.add(
                new Disagreement(
                    subject,
                    predicate,
                    r.winner().object(),
                    layerStr(r.winner().envelope().layer()),
                    derivedLosers));
          }
        }
      } else if (res instanceof Resolution.Contested c) {
        List<FlaggedEnvelope> candidates = new ArrayList<>();
        for (Triple t : c.candidates()) {
          candidates.add(toFlagged(t));
        }
        conflicts.add(new Conflict(subject, predicate, candidates, c.reason()));
      }
    }

    return new Result(disagreements, conflicts);
  }

  private static Triple toTriple(FlureeClient.ScopedEnvelope se) {
    FlureeClient.Envelope env = se.envelope();
    Layer layer = "authored".equals(env.layer()) ? Layer.AUTHORED : Layer.DERIVED;
    // queryAllEnvelopes does not return the asserted timestamp; recency never decides here.
    return new Triple(
        se.subject(),
        se.predicate(),
        env.object(),
        new ProvenanceEnvelope(layer, env.source(), Instant.EPOCH, env.conf()),
        true);
  }

  private static FlaggedEnvelope toFlagged(Triple t) {
    ProvenanceEnvelope env = t.envelope();
    return new FlaggedEnvelope(t.object(), layerStr(env.layer()), env.source(), env.conf());
  }

  private static String layerStr(Layer layer) {
    return layer == Layer.AUTHORED ? "authored" : "derived";
  }
}
