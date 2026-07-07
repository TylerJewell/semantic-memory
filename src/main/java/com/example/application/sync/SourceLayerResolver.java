package com.example.application.sync;

import com.example.domain.Layer;
import java.util.Collection;

/**
 * Maps a source path to its {@link Layer} (EC-011). Authored paths live under one base dir; corpus
 * (derived) paths under another. Overlapping globs are rejected at construction (on_overlap =
 * error). Pure logic — no Fluree/HTTP/LLM/Akka.
 */
public final class SourceLayerResolver {

  private final String authoredBase;
  private final String corpusBase;

  public SourceLayerResolver(String authoredGlob, String corpusGlob) {
    this.authoredBase = baseDir(authoredGlob);
    this.corpusBase = baseDir(corpusGlob);
    if (overlaps(authoredBase, corpusBase)) {
      throw new IllegalArgumentException("on_overlap");
    }
  }

  /** Layer for a path: authored -> AUTHORED, corpus -> DERIVED, neither -> error. */
  public Layer layerFor(String path) {
    if (matchesBase(path, authoredBase)) {
      return Layer.AUTHORED;
    }
    if (matchesBase(path, corpusBase)) {
      return Layer.DERIVED;
    }
    throw new IllegalArgumentException("no_layer_for_path: " + path);
  }

  /**
   * Vault-leak guard: is a corpus upload substantially a copy of a vault file? True on
   * normalized-equality OR &gt;0.8 line-overlap ratio with any vault file.
   */
  public static boolean isVaultLeak(String corpusContent, Collection<String> vaultFileContents) {
    String normCorpus = normalize(corpusContent);
    java.util.Set<String> corpusLines = lineSet(corpusContent);
    for (String vault : vaultFileContents) {
      if (normalize(vault).equals(normCorpus)) {
        return true;
      }
      java.util.Set<String> vaultLines = lineSet(vault);
      if (vaultLines.isEmpty()) {
        continue;
      }
      int overlap = 0;
      for (String line : corpusLines) {
        if (vaultLines.contains(line)) {
          overlap++;
        }
      }
      int denom = Math.max(corpusLines.size(), vaultLines.size());
      if (denom > 0 && (double) overlap / denom > 0.8) {
        return true;
      }
    }
    return false;
  }

  /** Base dir for a {@code base/**} glob: the portion before {@code **} (e.g. "vault/"). */
  private static String baseDir(String glob) {
    int idx = glob.indexOf("**");
    String base = idx >= 0 ? glob.substring(0, idx) : glob;
    if (!base.endsWith("/") && !base.isEmpty()) {
      base = base + "/";
    }
    return base;
  }

  private static boolean matchesBase(String path, String base) {
    return path.startsWith(base);
  }

  /** Overlap = same base dir, or one base dir is a prefix of the other. */
  private static boolean overlaps(String a, String b) {
    return a.startsWith(b) || b.startsWith(a);
  }

  private static String normalize(String s) {
    return s == null ? "" : s.strip().replaceAll("\\s+", " ");
  }

  private static java.util.Set<String> lineSet(String s) {
    java.util.Set<String> out = new java.util.HashSet<>();
    if (s == null) {
      return out;
    }
    for (String line : s.split("\\R")) {
      String t = line.strip();
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return out;
  }
}
