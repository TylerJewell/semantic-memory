package com.example.application.ingest;

import com.example.application.conflict.EntityResolver;
import com.example.domain.Layer;
import com.example.domain.Triple;
import java.util.ArrayList;
import java.util.List;

/** Straightforward in-memory {@link TripleStore}. Negations are held separately from triples. */
public final class InMemoryTripleStore implements TripleStore {

  private record Negation(String subject, String predicate, String object) {}

  private record Corroboration(Triple derived, Triple authoredMatch) {}

  private final List<Triple> triples = new ArrayList<>();
  private final List<Negation> negations = new ArrayList<>();
  private final List<Corroboration> corroborations = new ArrayList<>();
  private final EntityResolver resolver;

  /** @param resolver used to alias-resolve subjects/predicates/objects when matching. */
  public InMemoryTripleStore(EntityResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public List<Triple> authoredMatching(String subject, String predicate) {
    List<Triple> out = new ArrayList<>();
    for (Triple t : triples) {
      if (t.active()
          && t.envelope().layer() == Layer.AUTHORED
          && resolver.sameEntity(t.subject(), subject)
          && t.predicate().equals(predicate)) {
        out.add(t);
      }
    }
    return out;
  }

  @Override
  public List<Triple> allActive() {
    List<Triple> out = new ArrayList<>();
    for (Triple t : triples) {
      if (t.active()) {
        out.add(t);
      }
    }
    return out;
  }

  @Override
  public void add(Triple t) {
    triples.add(t);
  }

  @Override
  public void recordCorroboration(Triple derived, Triple authoredMatch) {
    corroborations.add(new Corroboration(derived, authoredMatch));
  }

  @Override
  public int corroborationCount() {
    return corroborations.size();
  }

  @Override
  public boolean hasActiveNegation(String subject, String predicate, String object) {
    for (Negation n : negations) {
      if (resolver.sameEntity(n.subject(), subject)
          && n.predicate().equals(predicate)
          && resolver.sameEntity(n.object(), object)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void addNegation(String subject, String predicate, String object) {
    negations.add(new Negation(subject, predicate, object));
  }
}
