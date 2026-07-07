package com.example.application.conflict;

import com.example.domain.Layer;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, layer-aware lexicographic cascade for resolving conflicts among ACTIVE triples that
 * share subject+predicate on a FUNCTIONAL predicate but disagree on object.
 *
 * <p>Cascade order: layer-precedence -> source-priority -> confidence -> recency -> terminal(flag).
 */
public final class ResolutionCascade {

  private ResolutionCascade() {}

  /**
   * @param group the conflicting triples (only ACTIVE ones participate).
   * @param sourcePriority ordered buckets, highest priority first; each bucket is a list of
   *     source-path prefixes of EQUAL priority. A triple's rank = index of the first bucket
   *     containing a prefix that {@code triple.envelope().source()} startsWith; unmatched sources
   *     rank AFTER all listed buckets (lowest priority).
   */
  public static Resolution resolve(List<Triple> group, List<List<String>> sourcePriority) {
    List<Triple> active = new ArrayList<>();
    for (Triple t : group) {
      if (t.active()) {
        active.add(t);
      }
    }

    if (active.isEmpty()) {
      throw new IllegalArgumentException("No active triples to resolve");
    }
    if (active.size() == 1) {
      return new Resolution.Resolved(active.get(0), "single", List.of());
    }

    // 2. layer-precedence
    boolean anyAuthored = active.stream().anyMatch(t -> t.envelope().layer() == Layer.AUTHORED);
    Layer topLayer = anyAuthored ? Layer.AUTHORED : Layer.DERIVED;

    List<Triple> top = new ArrayList<>();
    List<Triple> losers = new ArrayList<>();
    for (Triple t : active) {
      if (t.envelope().layer() == topLayer) {
        top.add(t);
      } else {
        losers.add(t);
      }
    }

    if (top.size() == 1) {
      return new Resolution.Resolved(top.get(0), "layer-precedence", losers);
    }

    // 3. source-priority
    int bestRank = Integer.MAX_VALUE;
    for (Triple t : top) {
      bestRank = Math.min(bestRank, rank(t, sourcePriority));
    }
    List<Triple> tied = new ArrayList<>();
    for (Triple t : top) {
      if (rank(t, sourcePriority) == bestRank) {
        tied.add(t);
      }
    }
    if (tied.size() == 1) {
      return new Resolution.Resolved(tied.get(0), "source-priority", others(active, tied.get(0)));
    }

    if (topLayer == Layer.DERIVED) {
      // 4. confidence
      double maxConf = Double.NEGATIVE_INFINITY;
      for (Triple t : tied) {
        maxConf = Math.max(maxConf, t.envelope().conf());
      }
      List<Triple> byConf = new ArrayList<>();
      for (Triple t : tied) {
        if (t.envelope().conf() == maxConf) {
          byConf.add(t);
        }
      }
      if (byConf.size() == 1) {
        return new Resolution.Resolved(byConf.get(0), "confidence", others(active, byConf.get(0)));
      }

      // 5. recency
      Instant maxAsserted = null;
      for (Triple t : byConf) {
        Instant a = t.envelope().asserted();
        if (maxAsserted == null || a.isAfter(maxAsserted)) {
          maxAsserted = a;
        }
      }
      List<Triple> byRecency = new ArrayList<>();
      for (Triple t : byConf) {
        if (t.envelope().asserted().equals(maxAsserted)) {
          byRecency.add(t);
        }
      }
      if (byRecency.size() == 1) {
        return new Resolution.Resolved(
            byRecency.get(0), "recency", others(active, byRecency.get(0)));
      }
    }

    // 6. terminal
    String reason = topLayer == Layer.AUTHORED ? "authored-tie" : "same-layer-no-discriminator";
    return new Resolution.Contested(tied, reason);
  }

  private static int rank(Triple t, List<List<String>> sourcePriority) {
    String source = t.envelope().source();
    for (int i = 0; i < sourcePriority.size(); i++) {
      for (String prefix : sourcePriority.get(i)) {
        if (source.startsWith(prefix)) {
          return i;
        }
      }
    }
    return sourcePriority.size();
  }

  private static List<Triple> others(List<Triple> active, Triple winner) {
    List<Triple> rest = new ArrayList<>();
    for (Triple t : active) {
      if (t != winner) {
        rest.add(t);
      }
    }
    return rest;
  }
}
