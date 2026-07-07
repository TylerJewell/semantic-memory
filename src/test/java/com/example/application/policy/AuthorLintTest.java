package com.example.application.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorLintTest {

  private static final Set<String> FUNCTIONAL = Set.of("homeCity", "birthDate");
  // Both sources share one equal-priority bucket, so source-priority cannot break the tie.
  private static final List<List<String>> SOURCE_PRIORITY = List.of(List.of("vault/"));

  private static Triple authored(String subject, String predicate, String object, String source) {
    return new Triple(
        subject,
        predicate,
        object,
        new ProvenanceEnvelope(Layer.AUTHORED, source, Instant.parse("2026-01-01T00:00:00Z"), 1.0),
        true);
  }

  @Test
  void conflictingAuthoredFactsAreFlagged() {
    List<Triple> facts =
        List.of(
            authored("alice", "homeCity", "Paris", "vault/people/a.md"),
            authored("alice", "homeCity", "Berlin", "vault/people/b.md"));

    List<String> conflicts = AuthorLint.lint(facts, SOURCE_PRIORITY, FUNCTIONAL);

    assertFalse(conflicts.isEmpty());
    assertTrue(conflicts.get(0).contains("alice"));
    assertTrue(conflicts.get(0).contains("homeCity"));
    assertTrue(conflicts.get(0).contains("Paris"));
    assertTrue(conflicts.get(0).contains("Berlin"));
    assertTrue(AuthorLint.hasConflicts(facts, SOURCE_PRIORITY, FUNCTIONAL));
  }

  @Test
  void cleanFactsProduceNoConflicts() {
    List<Triple> facts =
        List.of(
            authored("alice", "homeCity", "Paris", "vault/people/a.md"),
            authored("bob", "homeCity", "Berlin", "vault/people/b.md"));

    List<String> conflicts = AuthorLint.lint(facts, SOURCE_PRIORITY, FUNCTIONAL);

    assertTrue(conflicts.isEmpty());
    assertFalse(AuthorLint.hasConflicts(facts, SOURCE_PRIORITY, FUNCTIONAL));
  }
}
