package com.example.application.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PolicyParserTest {

  // Reference "personal assistant" policy from contracts/policy-md.md.
  private static final String REFERENCE_POLICY =
      """
      # Memory Policy — Personal Assistant

      ## Sources
          authored = vault/**
          corpus   = corpus/**
          on_overlap = error
          authored.extraction = assist-only

      ## Precedence
          precedence      = authored > derived
          source_priority = vault/people/ > vault/orgs/ > vault/imports/

      ## Cardinality
          functional   = birthDate, homeCity, currentEmployer
          multi_valued = *

      ## Resolution
          resolve_by.authored = layer-precedence, source-priority
          resolve_by.derived  = layer-precedence, source-priority, confidence, recency

      ## Conflict Resolution
          on_conflict     = flag
          scope           = functional-only
          contested_read  = freeze-incumbent

      ## Ingest Gate
          on_exact_file_dup    = reject
          on_vault_leak        = reject
          on_authored_match    = corroborate
          on_authored_conflict = flag
          report               = summary

      ## Resolution Quality
          resolution = alias-aware

      ## Deletion
          on_source_removal = orphan
          re_extraction     = require-review
          negation          = authored-only

      ## Provenance
          retention = full
      """;

  @Test
  void parsesReferencePolicyDirectives() {
    Policy policy = PolicyParser.parse(REFERENCE_POLICY);

    assertEquals("flag", policy.onConflict());
    assertTrue(policy.precedence().contains("authored"));
    assertEquals("freeze-incumbent", policy.contestedRead());
    assertEquals("alias-aware", policy.resolution());
    assertTrue(policy.functionalPredicates().contains("birthDate"));
    assertTrue(policy.functionalPredicates().contains("currentEmployer"));

    // source_priority parsed into ordered buckets
    assertEquals(3, policy.sourcePriority().size());
    assertEquals("vault/people/", policy.sourcePriority().get(0).get(0));
  }

  @Test
  void ignoresProse() {
    Policy policy = PolicyParser.parse(REFERENCE_POLICY);
    // The prose title line and blank lines produce no directives.
    assertEquals(4, policy.section("Sources").size());
    // Inline comment stripping: value has no trailing '#'.
    assertEquals("vault/**", policy.section("Sources").get("authored"));
  }

  @Test
  void unknownKeyThrows() {
    String bogus =
        """
        ## Conflict Resolution
            on_bogus = x
        """;
    assertThrows(IllegalArgumentException.class, () -> PolicyParser.parse(bogus));
  }

  @Test
  void overlapCollisionThrows() {
    String overlap =
        """
        ## Sources
            authored = shared/**
            corpus   = shared/**
        """;
    assertThrows(IllegalArgumentException.class, () -> PolicyParser.parse(overlap));
  }

  @Test
  void inlineCommentIsStripped() {
    String withComment =
        """
        ## Conflict Resolution
            on_conflict = flag   # everyday default
        """;
    Policy policy = PolicyParser.parse(withComment);
    assertEquals("flag", policy.onConflict());
  }

  @Test
  void presetResourcesParse() throws IOException {
    for (String name :
        new String[] {"personal-assistant.md", "compliance-legal.md", "eng-runbook.md"}) {
      String md = readResource("/policies/" + name);
      assertDoesNotThrow(() -> PolicyParser.parse(md), name + " should parse");
    }
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = PolicyParserTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("Resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
