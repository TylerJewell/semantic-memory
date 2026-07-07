package com.example.application;

import com.example.application.conflict.EntityResolver;
import com.example.application.ingest.FlureeTripleStore;
import com.example.application.ingest.IngestGate;
import com.example.application.ingest.IngestReport;
import com.example.domain.KnowledgeGraph;
import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the local Fluree HTTP server — the unified graph + vector +
 * provenance store this project uses in place of a separate triple store, vector
 * DB, and lexical index.
 *
 * <p>{@code remember} writes the extracted graph and the chunk's embedding in one
 * immutable transaction (Fluree returns a cryptographic commit hash — the audit
 * trail). {@code similarWithScores} does an inline cosine-similarity vector
 * search. {@code entityNeighborhood} walks one hop of the graph from matched
 * chunks. {@code bm25Search} runs full-text keyword ranking.
 */
public final class FlureeClient {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper OM = new ObjectMapper();
  private static final String BASE = "http://127.0.0.1:8090";
  private static final String LEDGER = "memory:main";
  private static final String EX = "http://example.org/";
  private static final Map<String, Object> CONTEXT =
      Map.of("ex", EX, "f", "https://ns.flur.ee/db#");

  private FlureeClient() {}

  static String slug(String s) {
    return s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
  }

  /** Persist chunk text + embedding + extracted graph; return the commit hash. */
  public static String remember(String text, KnowledgeGraph kg, double[] embedding) {
    try {
      List<Map<String, Object>> graph = new ArrayList<>();
      String chunkId = "ex:chunk_" + Integer.toHexString(text.hashCode());

      Map<String, Object> chunk = new LinkedHashMap<>();
      chunk.put("@id", chunkId);
      chunk.put("@type", "ex:Chunk");
      chunk.put("ex:text", text);
      chunk.put("ex:embedding", Map.of("@value", toList(embedding), "@type", "@vector"));
      graph.add(chunk);

      for (KnowledgeGraph.Entity e : kg.entities()) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@id", "ex:" + slug(e.name()));
        node.put("@type", "ex:" + (e.type() == null ? "Entity" : e.type().replaceAll("\\s+", "")));
        node.put("ex:name", e.name());
        node.put("ex:mentionedIn", Map.of("@id", chunkId));
        graph.add(node);
      }
      for (KnowledgeGraph.Relationship r : kg.relationships()) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("@id", "ex:" + slug(r.source()));
        s.put("ex:" + slug(r.label()), Map.of("@id", "ex:" + slug(r.target())));
        graph.add(s);
      }

      Map<String, Object> payload = Map.of("@context", CONTEXT, "@graph", graph);
      String resp =
          post("/v1/fluree/insert?ledger=" + LEDGER, OM.writeValueAsString(payload), "application/json");
      JsonNode node = OM.readTree(resp);
      String hash = node.path("commit").path("hash").asText("");
      return hash.isEmpty() ? node.path("tx-id").asText("(committed)") : hash;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Persist an extracted graph as provenanced {@code ex:Assertion} nodes on the unified fact store.
   * Each relationship becomes a DERIVED assertion (subject/predicate/object from the relationship,
   * source {@code "prose:<hash>"}, confidence 0.7) and is run through the {@link IngestGate} so it
   * participates in the SAME reconciliation cascade as authored/synced facts — corroborate,
   * conflict, and new are all handled. Returns the {@link IngestReport} summarizing the batch.
   */
  public static IngestReport rememberAsProse(String text, KnowledgeGraph kg) {
    FlureeTripleStore store = new FlureeTripleStore();
    EntityResolver resolver = new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, Map.of());
    IngestReport report = new IngestReport();
    Instant now = Instant.now();
    String source = "prose:" + Integer.toHexString(text.hashCode());
    for (KnowledgeGraph.Relationship r : kg.relationships()) {
      Triple derived =
          new Triple(
              r.source(),
              r.label(),
              r.target(),
              new ProvenanceEnvelope(Layer.DERIVED, source, now, 0.7),
              true);
      IngestGate.ingest(derived, store, resolver, report);
    }
    return report;
  }

  /** A single ranked memory hit returned by similarity / lexical search. */
  public record Hit(String text, double score) {}

  /** Total number of stored chunks, entities, and Fluree commits. */
  public record Stats(int chunks, int entities, int commits) {}

  /** Inline cosine-similarity search returning text + score. */
  public static List<Hit> similarWithScores(double[] queryVec, int limit) {
    try {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?text", "?score"));
      q.put(
          "where",
          List.of(
              Map.of("@id", "?c", "ex:text", "?text", "ex:embedding", "?vec"),
              List.of("bind", "?score", "(cosineSimilarity ?vec ?qv)")));
      q.put(
          "values",
          List.of(
              List.of("?qv"),
              List.of(Map.of("@value", toList(queryVec), "@type", "f:embeddingVector"))));
      q.put("orderBy", List.of(List.of("desc", "?score")));
      q.put("limit", limit);

      String resp =
          post("/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<Hit> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() >= 2) {
            String txt = row.get(0).asText();
            if (!txt.isBlank()) {
              out.add(new Hit(txt, row.get(1).asDouble()));
            }
          }
        }
      }
      return out;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Inline cosine-similarity search over chunk embeddings; returns top texts. */
  public static List<String> similar(double[] queryVec, int limit) {
    try {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?text", "?score"));
      q.put(
          "where",
          List.of(
              Map.of("@id", "?c", "ex:text", "?text", "ex:embedding", "?vec"),
              List.of("bind", "?score", "(cosineSimilarity ?vec ?qv)")));
      q.put(
          "values",
          List.of(
              List.of("?qv"),
              List.of(Map.of("@value", toList(queryVec), "@type", "f:embeddingVector"))));
      q.put("orderBy", List.of(List.of("desc", "?score")));
      q.put("limit", limit);

      String resp =
          post("/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<String> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() > 0) {
            String txt = row.get(0).asText();
            if (!txt.isBlank()) out.add(txt);
          }
        }
      }
      return out;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Raw SPARQL query (must include FROM &lt;memory:main&gt;); returns the JSON body. */
  public static String sparql(String query) {
    return post("/v1/fluree/query?ledger=" + LEDGER, query, "application/sparql-query");
  }

  /**
   * For each provided chunk text, walk to entities mentionedIn that chunk and
   * pull one hop of outgoing predicates. Returns formatted triples like
   * {@code "Akka --provides--> Resilience"} — one-hop graph context around the
   * given chunks.
   */
  public static List<String> entityNeighborhood(List<String> chunkTexts, int limit) {
    if (chunkTexts.isEmpty()) {
      return List.of();
    }
    try {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?sName", "?p", "?tName"));
      q.put(
          "where",
          List.of(
              Map.of("@id", "?c", "ex:text", "?text"),
              Map.of("@id", "?s", "ex:mentionedIn", Map.of("@id", "?c"), "ex:name", "?sName"),
              Map.of("@id", "?s", "?p", Map.of("@id", "?t")),
              Map.of("@id", "?t", "ex:name", "?tName")));
      q.put("values", List.of(List.of("?text"), chunkTexts.stream().map(List::of).toList()));
      q.put("limit", limit);

      String resp =
          post("/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<String> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() >= 3) {
            String p = row.get(1).asText();
            String pred = p.startsWith(EX) ? p.substring(EX.length()).replace('_', ' ') : p;
            out.add(row.get(0).asText() + " --" + pred + "--> " + row.get(2).asText());
          }
        }
      }
      return out;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * BM25 keyword search over chunk text. Requires a pre-created Fluree full-text
   * index named {@code chunk-search}. Returns chunk texts ranked by score. If the
   * index is not present we degrade to an empty list so the demo still answers.
   */
  public static List<String> bm25Search(String query, int limit) {
    try {
      Map<String, Object> search = new LinkedHashMap<>();
      search.put("f:graphSource", "chunk-search:main");
      search.put("f:searchText", query);
      search.put("f:searchLimit", limit);
      search.put(
          "f:searchResult",
          Map.of("f:resultId", "?chunk", "f:resultScore", "?score"));

      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?text", "?score"));
      q.put("where", List.of(search, Map.of("@id", "?chunk", "ex:text", "?text")));
      q.put("orderBy", List.of(List.of("desc", "?score")));
      q.put("limit", limit);

      String resp =
          post("/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<String> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() > 0) {
            out.add(row.get(0).asText());
          }
        }
      }
      return out;
    } catch (Exception e) {
      // Most likely the BM25 index hasn't been created. Don't break the API.
      return List.of();
    }
  }

  /** Count chunks (subjects with ex:text), entities (subjects with ex:name), and commits. */
  public static Stats stats() {
    int chunks = sparqlCount("?s <" + EX + "text> ?v");
    int entities = sparqlCount("?s <" + EX + "name> ?v");
    // In this demo one Fluree transaction = one remember, so commits tracks chunks.
    return new Stats(chunks, entities, chunks);
  }

  /** Distinct assertion subjects and total {@code ex:Assertion} nodes in the fact store. */
  public record AssertionStats(int subjects, int assertions) {}

  /**
   * Count distinct subjects and total assertions across the provenanced {@code ex:Assertion} nodes
   * — the observe surface for the unified fact model (mirrors {@link #sparqlCount}).
   */
  public static AssertionStats assertionStats() {
    int subjects = sparqlCountVar("?s", "?a <" + EX + "subject> ?s");
    int assertions = sparqlCountVar("?a", "?a <" + EX + "subject> ?s");
    return new AssertionStats(subjects, assertions);
  }

  private static int sparqlCountVar(String countVar, String pattern) {
    try {
      String q =
          "SELECT (COUNT(DISTINCT "
              + countVar
              + ") AS ?n) FROM <"
              + LEDGER
              + "> WHERE { "
              + pattern
              + " }";
      JsonNode root = OM.readTree(sparql(q));
      JsonNode bindings = root.at("/results/bindings");
      if (bindings.isArray() && bindings.size() > 0) {
        return bindings.get(0).at("/n/value").asInt(0);
      }
    } catch (Exception ignored) {
    }
    return 0;
  }

  /** Most recent {@code limit} assertion facts as {@code "subject predicate object"} strings. */
  public static List<String> recentAssertions(int limit) {
    try {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?s", "?p", "?o"));
      q.put(
          "where",
          List.of(
              Map.of(
                  "@id",
                  "?a",
                  "@type",
                  "ex:Assertion",
                  "ex:subject",
                  "?s",
                  "ex:predicate",
                  "?p",
                  "ex:object",
                  "?o")));
      q.put("limit", Math.max(1, limit));
      String resp =
          post("/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<String> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() >= 3) {
            out.add(row.get(0).asText() + " " + row.get(1).asText() + " " + row.get(2).asText());
          }
        }
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static int sparqlCount(String pattern) {
    try {
      String q =
          "SELECT (COUNT(DISTINCT ?s) AS ?n) FROM <" + LEDGER + "> WHERE { " + pattern + " }";
      JsonNode root = OM.readTree(sparql(q));
      JsonNode bindings = root.at("/results/bindings");
      if (bindings.isArray() && bindings.size() > 0) {
        return bindings.get(0).at("/n/value").asInt(0);
      }
    } catch (Exception ignored) {
    }
    return 0;
  }

  /** Most recent {@code limit} chunk texts, freshest first. */
  public static List<String> recentChunks(int limit) {
    try {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?id", "?text"));
      q.put("where", List.of(Map.of("@id", "?id", "@type", "ex:Chunk", "ex:text", "?text")));
      q.put("limit", Math.max(1, limit));
      String resp = post(
          "/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<String> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() >= 2) {
            out.add(row.get(1).asText());
          }
        }
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  /** Delete every chunk + its triples. Used by /api/forget. */
  public static void forgetAll() {
    try {
      // SPARQL DELETE WHERE — wipes every triple in the ledger.
      String del = "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }";
      post("/v1/fluree/update?ledger=" + LEDGER, del, "application/sparql-update");
    } catch (Exception ignored) {
    }
  }

  /**
   * Persist a per-triple provenance assertion (reification) as an {@code ex:Assertion}
   * node: the slugged subject/predicate/object plus the envelope (layer, source,
   * asserted timestamp, confidence) and an active flag. Returns the commit hash,
   * like {@link #remember}. This is the persistence foundation the conflict engine
   * queries via {@link #queryEnvelopes}.
   */
  public static String insertAssertion(com.example.domain.Triple t) {
    try {
      com.example.domain.ProvenanceEnvelope env = t.envelope();
      String subj = slug(t.subject());
      String pred = slug(t.predicate());
      String obj = slug(t.object());
      String layer =
          env.layer() == com.example.domain.Layer.AUTHORED ? "authored" : "derived";
      String key = subj + "|" + pred + "|" + obj + "|" + env.source() + "|" + env.asserted();
      String assertId = "ex:assert_" + Integer.toHexString(key.hashCode());

      Map<String, Object> node = new LinkedHashMap<>();
      node.put("@id", assertId);
      node.put("@type", "ex:Assertion");
      node.put("ex:subject", subj);
      node.put("ex:predicate", pred);
      node.put("ex:object", obj);
      node.put("ex:layer", layer);
      node.put("ex:source", env.source());
      node.put("ex:asserted", env.asserted().toString());
      node.put("ex:conf", env.conf());
      node.put("ex:active", t.active());

      Map<String, Object> payload = Map.of("@context", CONTEXT, "@graph", List.of(node));
      String resp =
          post("/v1/fluree/insert?ledger=" + LEDGER, OM.writeValueAsString(payload), "application/json");
      JsonNode r = OM.readTree(resp);
      String hash = r.path("commit").path("hash").asText("");
      return hash.isEmpty() ? r.path("tx-id").asText("(committed)") : hash;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** A provenance envelope as stored on an {@code ex:Assertion} node. */
  public record Envelope(String object, String layer, String source, double conf) {}

  /**
   * Query provenance assertions for a given subject + predicate (both slugged),
   * returning one {@link Envelope} per matching {@code ex:Assertion} node. Mirrors
   * the JSON-LD query pattern used elsewhere in this client. Degrades to an empty
   * list on error, like {@link #bm25Search}.
   */
  public static java.util.List<Envelope> queryEnvelopes(String subject, String predicate) {
    try {
      Map<String, Object> where = new LinkedHashMap<>();
      where.put("@id", "?a");
      where.put("@type", "ex:Assertion");
      where.put("ex:subject", slug(subject));
      where.put("ex:predicate", slug(predicate));
      where.put("ex:object", "?object");
      where.put("ex:layer", "?layer");
      where.put("ex:source", "?source");
      where.put("ex:conf", "?conf");

      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?object", "?layer", "?source", "?conf"));
      q.put("where", where);

      String resp =
          post("/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<Envelope> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() >= 4) {
            out.add(
                new Envelope(
                    row.get(0).asText(),
                    row.get(1).asText(),
                    row.get(2).asText(),
                    row.get(3).asDouble()));
          }
        }
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  /** An {@link Envelope} together with the subject + predicate it was asserted on. */
  public record ScopedEnvelope(String subject, String predicate, Envelope envelope) {}

  /**
   * Query every {@code ex:Assertion} node in the ledger, returning one
   * {@link ScopedEnvelope} (subject + predicate + envelope) per row. Mirrors
   * {@link #queryEnvelopes} but without a subject/predicate filter — the store
   * backing the ingest gate uses this for {@code allActive()}. Degrades to an
   * empty list on error.
   */
  public static java.util.List<ScopedEnvelope> queryAllEnvelopes() {
    try {
      Map<String, Object> where = new LinkedHashMap<>();
      where.put("@id", "?a");
      where.put("@type", "ex:Assertion");
      where.put("ex:subject", "?subject");
      where.put("ex:predicate", "?predicate");
      where.put("ex:object", "?object");
      where.put("ex:layer", "?layer");
      where.put("ex:source", "?source");
      where.put("ex:conf", "?conf");

      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("?subject", "?predicate", "?object", "?layer", "?source", "?conf"));
      q.put("where", where);

      String resp =
          post("/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      List<ScopedEnvelope> out = new ArrayList<>();
      if (arr.isArray()) {
        for (JsonNode row : arr) {
          if (row.isArray() && row.size() >= 6) {
            out.add(
                new ScopedEnvelope(
                    row.get(0).asText(),
                    row.get(1).asText(),
                    new Envelope(
                        row.get(2).asText(),
                        row.get(3).asText(),
                        row.get(4).asText(),
                        row.get(5).asDouble())));
          }
        }
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static List<Double> toList(double[] a) {
    List<Double> l = new ArrayList<>(a.length);
    for (double d : a) {
      l.add(d);
    }
    return l;
  }

  private static String post(String path, String body, String contentType) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(BASE + path))
              .header("Content-Type", contentType)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 300) {
        throw new RuntimeException("Fluree " + path + " -> " + resp.statusCode() + ": " + resp.body());
      }
      return resp.body();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
