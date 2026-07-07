package com.example.application.ingest;

import com.example.application.FlureeClient;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link TripleStore} backed by the live Fluree ledger via {@link FlureeClient}. This is the bridge
 * that lets the pure ingest gate persist to and query the real provenance store instead of an
 * in-memory map.
 *
 * <p>Corroboration counts and authored negations are kept in process — EC-040/043 do not exercise
 * them, and persisting them is a follow-up (see TODOs).
 */
public final class FlureeTripleStore implements TripleStore {

  private int corroborations;
  private final Set<String> negations = new HashSet<>();

  private static Triple toTriple(String subject, String predicate, FlureeClient.Envelope env) {
    Layer layer = "authored".equals(env.layer()) ? Layer.AUTHORED : Layer.DERIVED;
    return new Triple(
        subject,
        predicate,
        env.object(),
        new ProvenanceEnvelope(layer, env.source(), Instant.now(), env.conf()),
        true);
  }

  @Override
  public List<Triple> authoredMatching(String subject, String predicate) {
    List<Triple> out = new ArrayList<>();
    for (FlureeClient.Envelope env : FlureeClient.queryEnvelopes(subject, predicate)) {
      if ("authored".equals(env.layer())) {
        out.add(toTriple(subject, predicate, env));
      }
    }
    return out;
  }

  @Override
  public List<Triple> allActive() {
    List<Triple> out = new ArrayList<>();
    for (FlureeClient.ScopedEnvelope se : FlureeClient.queryAllEnvelopes()) {
      out.add(toTriple(se.subject(), se.predicate(), se.envelope()));
    }
    return out;
  }

  @Override
  public void add(Triple t) {
    FlureeClient.insertAssertion(t);
  }

  @Override
  public void recordCorroboration(Triple derived, Triple authoredMatch) {
    // In-process for now; not persisted as an assertion (not exercised by EC-040/043).
    corroborations++;
  }

  @Override
  public int corroborationCount() {
    return corroborations;
  }

  private static String negationKey(String subject, String predicate, String object) {
    return subject + "|" + predicate + "|" + object;
  }

  @Override
  public boolean hasActiveNegation(String subject, String predicate, String object) {
    // TODO: persist negations in Fluree. In-process set is sufficient for EC-040/043.
    return negations.contains(negationKey(subject, predicate, object));
  }

  @Override
  public void addNegation(String subject, String predicate, String object) {
    negations.add(negationKey(subject, predicate, object));
  }
}
