package com.example.application.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed model of a parsed {@code policy.md}. Directives are stored per recognized {@code ##}
 * section as a simple {@code key -> value} map; typed convenience getters cover the common ones.
 *
 * <p>Purely a data holder — parsing and validation live in {@link PolicyParser}.
 */
public final class Policy {

  /** Recognized keys per section, from the policy-md contract. */
  public static final Map<String, Set<String>> RECOGNIZED_KEYS = recognizedKeys();

  private final Map<String, Map<String, String>> sections;

  Policy(Map<String, Map<String, String>> sections) {
    this.sections = sections;
  }

  private static Map<String, Set<String>> recognizedKeys() {
    Map<String, Set<String>> m = new LinkedHashMap<>();
    m.put("Sources", Set.of("authored", "corpus", "on_overlap", "authored.extraction"));
    m.put("Precedence", Set.of("precedence", "source_priority"));
    m.put("Cardinality", Set.of("functional", "multi_valued"));
    m.put("Resolution", Set.of("resolve_by.authored", "resolve_by.derived"));
    m.put("Conflict Resolution", Set.of("on_conflict", "scope", "contested_read"));
    m.put(
        "Ingest Gate",
        Set.of(
            "on_exact_file_dup",
            "on_vault_leak",
            "on_authored_match",
            "on_authored_conflict",
            "report"));
    m.put("Resolution Quality", Set.of("resolution"));
    m.put("Deletion", Set.of("on_source_removal", "re_extraction", "negation"));
    m.put("Provenance", Set.of("retention"));
    return m;
  }

  /** @return the raw directives for {@code section}, or an empty map if the section is absent. */
  public Map<String, String> section(String section) {
    return sections.getOrDefault(section, Map.of());
  }

  /** @return the value for {@code key} under {@code section}, or {@code null} if absent. */
  public String get(String section, String key) {
    return section(section).get(key);
  }

  // ---- typed convenience getters ----

  public String precedence() {
    return get("Precedence", "precedence");
  }

  public String onConflict() {
    return get("Conflict Resolution", "on_conflict");
  }

  public String onAuthoredConflict() {
    return get("Ingest Gate", "on_authored_conflict");
  }

  public String contestedRead() {
    return get("Conflict Resolution", "contested_read");
  }

  public String resolution() {
    return get("Resolution Quality", "resolution");
  }

  /** Functional predicates, parsed from the comma-separated {@code functional} directive. */
  public Set<String> functionalPredicates() {
    String raw = get("Cardinality", "functional");
    Set<String> out = new LinkedHashSet<>();
    if (raw == null || raw.equals("*")) {
      return out;
    }
    for (String p : raw.split(",")) {
      String v = p.trim();
      if (!v.isEmpty()) {
        out.add(v);
      }
    }
    return out;
  }

  /**
   * Source priority as ordered equal-priority buckets. Segments are split on {@code >} (highest
   * priority first); items within a segment are split on {@code ,} into an equal-priority bucket.
   */
  public List<List<String>> sourcePriority() {
    String raw = get("Precedence", "source_priority");
    List<List<String>> out = new ArrayList<>();
    if (raw == null || raw.equals("*")) {
      return out;
    }
    for (String segment : raw.split(">")) {
      List<String> bucket = new ArrayList<>();
      for (String item : segment.split(",")) {
        String v = item.trim();
        if (!v.isEmpty()) {
          bucket.add(v);
        }
      }
      if (!bucket.isEmpty()) {
        out.add(bucket);
      }
    }
    return out;
  }
}
