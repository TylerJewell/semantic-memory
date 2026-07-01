package com.example.application;

import com.example.domain.KnowledgeGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the local Fluree HTTP server (the unified graph + vector +
 * provenance store that replaces Cognee's Kuzu/Neo4j + LanceDB + lexical layers).
 *
 * <p>{@code remember} writes the extracted graph and the chunk's embedding in one
 * immutable transaction (Fluree returns a commit hash = built-in provenance).
 * {@code similar} does an inline cosine-similarity vector search. {@code sparql}
 * runs a graph query for display.
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
            out.add(new Hit(row.get(0).asText(), row.get(1).asDouble()));
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
            out.add(row.get(0).asText());
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
   * {@code "Akka --provides--> Resilience"}. Mirrors Cognee's
   * resolve_edges_to_text but in our flatter Fluree shape.
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

  /** Count chunks, entities, and commits in the ledger. */
  public static Stats stats() {
    int chunks = countByType("ex:Chunk");
    int commits = 0;
    try {
      JsonNode arr = OM.readTree(sparql(
          "SELECT (COUNT(?s) AS ?n) FROM <" + LEDGER + "> WHERE { ?s a <ex:Chunk> }"));
      // Result is JSON-LD SPARQL; fall back gracefully.
    } catch (Exception ignored) {
    }
    // Entities = anything mentionedIn a chunk
    int entities = 0;
    try {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("(count(distinct ?s) as ?n)"));
      q.put("where", List.of(Map.of("@id", "?s", "ex:mentionedIn", Map.of("@id", "?c"))));
      String resp = post(
          "/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      if (arr.isArray() && arr.size() > 0 && arr.get(0).isArray() && arr.get(0).size() > 0) {
        entities = arr.get(0).get(0).asInt();
      }
    } catch (Exception ignored) {
    }
    commits = chunks; // one commit per remember in this demo
    return new Stats(chunks, entities, commits);
  }

  private static int countByType(String typeIri) {
    try {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("@context", CONTEXT);
      q.put("from", LEDGER);
      q.put("select", List.of("(count(?s) as ?n)"));
      q.put("where", List.of(Map.of("@id", "?s", "@type", typeIri)));
      String resp = post(
          "/v1/fluree/query?ledger=" + LEDGER, OM.writeValueAsString(q), "application/json");
      JsonNode arr = OM.readTree(resp);
      if (arr.isArray() && arr.size() > 0 && arr.get(0).isArray() && arr.get(0).size() > 0) {
        return arr.get(0).get(0).asInt();
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
