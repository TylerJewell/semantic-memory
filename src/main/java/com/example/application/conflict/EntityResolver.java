package com.example.application.conflict;

import java.util.Map;

public final class EntityResolver {
  public enum Mode {
    STRICT,
    ALIAS_AWARE,
    EMBEDDING_ASSISTED
  }

  private final Mode mode;
  private final Map<String, String> aliases; // key = normalized alias, value = canonical

  public EntityResolver(Mode mode, Map<String, String> aliases) {
    this.mode = mode;
    this.aliases = Map.copyOf(aliases);
  }

  /**
   * Normalize: trim, lowercase, collapse internal whitespace, strip a trailing corporate
   * suffix/punctuation such as ", inc", " inc", " corp", " co", " llc".
   */
  private static String normalize(String name) {
    if (name == null) {
      return "";
    }
    String n = name.trim().toLowerCase().replaceAll("\\s+", " ");
    // strip trailing punctuation (e.g. "acme." -> "acme")
    n = n.replaceAll("[.,;]+$", "").trim();
    return n;
  }

  private static String stripSuffix(String normalized) {
    String n = normalized;
    // repeatedly strip trailing corporate suffixes and separating punctuation
    boolean changed = true;
    while (changed) {
      changed = false;
      String stripped =
          n.replaceAll("[\\s,]*(,\\s*)?(inc|incorporated|corp|corporation|co|llc|ltd)\\.?$", "")
              .trim();
      stripped = stripped.replaceAll("[.,;]+$", "").trim();
      if (!stripped.equals(n)) {
        n = stripped;
        changed = true;
      }
    }
    return n;
  }

  public String canonical(String name) {
    String normalized = normalize(name);
    return switch (mode) {
      case STRICT -> normalized;
      // EMBEDDING_ASSISTED behaves like ALIAS_AWARE for now.
      // TODO: use embeddings to resolve near-duplicate entity names to a canonical form.
      case ALIAS_AWARE, EMBEDDING_ASSISTED -> {
        // Prefer an explicit alias entry when present (keyed by normalized name).
        // Alias targets are normalized so they converge with directly-normalized names.
        if (aliases.containsKey(normalized)) {
          yield normalize(aliases.get(normalized));
        }
        String stripped = stripSuffix(normalized);
        // An explicit alias may exist for the suffix-stripped form too.
        if (aliases.containsKey(stripped)) {
          yield normalize(aliases.get(stripped));
        }
        yield stripped;
      }
    };
  }

  public boolean sameEntity(String a, String b) {
    return canonical(a).equals(canonical(b));
  }
}
