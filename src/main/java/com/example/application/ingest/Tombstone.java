package com.example.application.ingest;

import com.example.domain.Triple;

/**
 * Deletion via tombstone: returns an inactive copy of a triple. An inactive triple does not assert
 * and cannot conflict — the resolution cascade already filters on {@code active}, so a tombstone is
 * simply skipped during resolution and by {@link TripleStore#authoredMatching}.
 */
public final class Tombstone {

  private Tombstone() {}

  public static Triple tombstone(Triple t) {
    return new Triple(t.subject(), t.predicate(), t.object(), t.envelope(), false);
  }
}
