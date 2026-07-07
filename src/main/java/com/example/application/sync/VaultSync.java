package com.example.application.sync;

import com.example.application.ingest.TripleStore;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Syncs vault (authored) and corpus (derived) files into a {@link TripleStore} (EC-010, EC-016).
 *
 * <p>Assist-only rule: a vault file persists AUTHORED triples ONLY from its YAML frontmatter; prose
 * is NEVER persisted as derived. A corpus file persists DERIVED triples from caller-supplied
 * extracted pairs. Pure logic — no Fluree/HTTP/LLM/Akka.
 */
public final class VaultSync {

  private VaultSync() {}

  /**
   * Parse frontmatter {@code key: value} pairs into AUTHORED triples about {@code subject}. Envelope
   * = {AUTHORED, source=path, asserted=now, conf=1.0}. Prose after the closing {@code ---} is
   * ignored (never derived). Adds each triple to the store.
   */
  public static List<Triple> syncVaultFile(
      String path, String subject, String content, Instant now, TripleStore store) {
    List<Triple> out = new ArrayList<>();
    for (String[] kv : parseFrontmatter(content)) {
      ProvenanceEnvelope env = new ProvenanceEnvelope(Layer.AUTHORED, path, now, 1.0);
      Triple t = new Triple(subject, kv[0], kv[1], env, true);
      store.add(t);
      out.add(t);
    }
    return out;
  }

  /**
   * Each caller-supplied {@code (predicate, object)} pair becomes a DERIVED triple. Envelope =
   * {DERIVED, source=path, asserted=now, conf=given}. Adds each triple to the store.
   */
  public static List<Triple> syncCorpusFile(
      String path,
      String subject,
      List<String[]> predObjPairs,
      double conf,
      Instant now,
      TripleStore store) {
    List<Triple> out = new ArrayList<>();
    for (String[] po : predObjPairs) {
      ProvenanceEnvelope env = new ProvenanceEnvelope(Layer.DERIVED, path, now, conf);
      Triple t = new Triple(subject, po[0], po[1], env, true);
      store.add(t);
      out.add(t);
    }
    return out;
  }

  /** Lines between the first two {@code ---} markers, parsed as {@code key: value}. */
  private static List<String[]> parseFrontmatter(String content) {
    List<String[]> out = new ArrayList<>();
    if (content == null) {
      return out;
    }
    String[] lines = content.split("\\R");
    int i = 0;
    // skip leading blank lines
    while (i < lines.length && lines[i].strip().isEmpty()) {
      i++;
    }
    if (i >= lines.length || !lines[i].strip().equals("---")) {
      return out; // no frontmatter block
    }
    i++; // past opening marker
    for (; i < lines.length; i++) {
      String line = lines[i];
      if (line.strip().equals("---")) {
        break; // closing marker; prose (everything after) ignored
      }
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = line.substring(0, colon).strip();
      String value = line.substring(colon + 1).strip();
      if (!key.isEmpty()) {
        out.add(new String[] {key, value});
      }
    }
    return out;
  }
}
