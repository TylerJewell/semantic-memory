package com.example.application.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for {@code policy.md}: markdown with indented {@code key = value} directives under
 * {@code ##} section headings. Prose and non-indented lines are ignored; {@code #} starts an
 * inline comment; {@code *} is the wildcard/default token.
 *
 * <p>Validation at parse time throws {@link IllegalArgumentException} on an unknown key (not in the
 * recognized set for its section) and on an {@code on_overlap} source collision (the same glob
 * claimed by both {@code authored} and {@code corpus}).
 */
public final class PolicyParser {

  /** Indented {@code key = value} directive. */
  private static final Pattern DIRECTIVE = Pattern.compile("^\\s+([^\\s=]+)\\s*=\\s*(.*)$");

  private PolicyParser() {}

  public static Policy parse(String markdown) {
    Map<String, Map<String, String>> sections = new LinkedHashMap<>();
    String currentSection = null;

    for (String rawLine : markdown.split("\n", -1)) {
      String line = stripComment(rawLine);

      // Section headings: exactly "## Title" (not "###"); headings are non-indented.
      if (rawLine.startsWith("## ") && !rawLine.startsWith("###")) {
        currentSection = rawLine.substring(3).trim();
        continue;
      }
      if (rawLine.startsWith("#")) {
        // Other headings / top-level comments — ignore.
        continue;
      }

      Matcher m = DIRECTIVE.matcher(line);
      if (!m.matches()) {
        continue; // prose or non-indented line
      }
      if (currentSection == null) {
        continue; // directive outside any section — ignore
      }

      String key = m.group(1).trim();
      String value = m.group(2).trim();

      Set<String> recognized = Policy.RECOGNIZED_KEYS.get(currentSection);
      if (recognized == null || !recognized.contains(key)) {
        throw new IllegalArgumentException(
            "Unknown key '" + key + "' in section '" + currentSection + "'");
      }

      sections.computeIfAbsent(currentSection, k -> new LinkedHashMap<>()).put(key, value);
    }

    validateOverlap(sections);
    return new Policy(sections);
  }

  private static String stripComment(String line) {
    int hash = line.indexOf('#');
    // A leading '#' marks a heading/comment line handled by the caller; only strip inline comments
    // that follow content on a directive line.
    if (hash > 0) {
      return line.substring(0, hash);
    }
    return line;
  }

  private static void validateOverlap(Map<String, Map<String, String>> sections) {
    Map<String, String> sources = sections.get("Sources");
    if (sources == null) {
      return;
    }
    String authored = sources.get("authored");
    String corpus = sources.get("corpus");
    if (authored != null && corpus != null && authored.equals(corpus)) {
      throw new IllegalArgumentException(
          "on_overlap: path glob '"
              + authored
              + "' is claimed by both authored and corpus sources");
    }
  }
}
