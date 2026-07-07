package com.example.application.ingest;

import com.example.domain.Triple;
import java.util.List;

/** In-memory store abstraction used by the ingest gate. Pure logic — no Fluree/HTTP/LLM. */
public interface TripleStore {

  /** ACTIVE authored triples with the same subject+predicate (caller may alias-resolve objects). */
  List<Triple> authoredMatching(String subject, String predicate);

  /** All ACTIVE triples currently asserted in the store. */
  List<Triple> allActive();

  /** Add a triple (assumed active) to the store. */
  void add(Triple t);

  /** Record corroboration evidence for a derived triple against an authored match — NOT a triple. */
  void recordCorroboration(Triple derived, Triple authoredMatch);

  /** Number of corroboration records accumulated. */
  int corroborationCount();

  /** True when an authored negation NOT(s p o) is active for this subject+predicate+object. */
  boolean hasActiveNegation(String subject, String predicate, String object);

  /** Assert an authored negation NOT(s p o). */
  void addNegation(String subject, String predicate, String object);
}
