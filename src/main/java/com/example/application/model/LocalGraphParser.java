package com.example.application.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.domain.KnowledgeGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the raw text a small local LLM emits for a graph-extraction prompt into a {@link
 * KnowledgeGraph}. Tiny models (e.g. TinyLlama) reliably emit a leading JSON object when few-shot
 * prompted, then ramble; this pulls out the first balanced {@code {...}} object and parses it, with
 * a lenient regex fallback via {@link StructuredOutputRepair}. Never returns null and never
 * fabricates a fact — if nothing parseable is present it returns an empty graph.
 */
public final class LocalGraphParser {

  private LocalGraphParser() {}

  private static final ObjectMapper OM =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final Pattern REL =
      Pattern.compile(
          "\"source\"\\s*:\\s*\"([^\"]*)\".*?\"label\"\\s*:\\s*\"([^\"]*)\".*?\"target\"\\s*:\\s*\"([^\"]*)\"",
          Pattern.DOTALL);

  /** Parse a raw model reply into a KnowledgeGraph (strict JSON first, then lenient regex). */
  public static KnowledgeGraph parse(String raw) {
    return StructuredOutputRepair.parseOrRepair(
            hint -> raw, // single captured reply; the local model isn't re-invoked here
            LocalGraphParser::strictParse,
            () -> lenientParse(raw),
            1)
        .value();
  }

  /** Extract the first balanced JSON object and parse it; throw if absent or has no relationship. */
  private static KnowledgeGraph strictParse(String raw) throws Exception {
    String json = firstJsonObject(raw);
    if (json == null) {
      throw new IllegalArgumentException("no JSON object in model output");
    }
    KnowledgeGraph kg = OM.readValue(json, KnowledgeGraph.class);
    List<KnowledgeGraph.Relationship> rels =
        kg.relationships() == null ? List.of() : kg.relationships();
    if (rels.isEmpty()) {
      throw new IllegalArgumentException("no relationships in model JSON");
    }
    List<KnowledgeGraph.Entity> ents = kg.entities() == null ? List.of() : kg.entities();
    return new KnowledgeGraph(ents, rels);
  }

  /** Last resort: regex out the first source/label/target triad; empty graph if none. */
  private static KnowledgeGraph lenientParse(String raw) {
    Matcher m = REL.matcher(raw);
    List<KnowledgeGraph.Relationship> rels = new ArrayList<>();
    if (m.find()
        && !m.group(1).isBlank()
        && !m.group(2).isBlank()
        && !m.group(3).isBlank()) {
      rels.add(new KnowledgeGraph.Relationship(m.group(1), m.group(2), m.group(3)));
    }
    return new KnowledgeGraph(List.of(), rels);
  }

  /** The first balanced-brace {@code {...}} substring, or null. */
  static String firstJsonObject(String s) {
    int start = s.indexOf('{');
    if (start < 0) {
      return null;
    }
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return s.substring(start, i + 1);
        }
      }
    }
    return null;
  }
}
