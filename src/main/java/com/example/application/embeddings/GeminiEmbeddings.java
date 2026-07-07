package com.example.application.embeddings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Turns text into a meaning-vector via Gemini's text-embedding-004 endpoint
 * (768 dimensions). Pure {@code java.net.http} — no extra dependency.
 *
 * <p>This is the one place we still call an external embedding service. Swapping
 * to a local in-process ONNX model (langchain4j-embeddings-all-minilm) is a
 * one-class change and removes even this external call.
 */
public final class GeminiEmbeddings implements Embeddings {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper OM = new ObjectMapper();
  private static final String KEY = System.getenv("GOOGLE_AI_GEMINI_API_KEY");
  private static final String URL =
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=";

  public GeminiEmbeddings() {}

  @Override
  public double[] embed(String text) {
    try {
      String body =
          OM.writeValueAsString(
              Map.of(
                  "model", "models/gemini-embedding-001",
                  "content", Map.of("parts", List.of(Map.of("text", text))),
                  "outputDimensionality", 768));
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(URL + KEY))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        throw new RuntimeException("Gemini embed failed: " + resp.statusCode() + " " + resp.body());
      }
      JsonNode values = OM.readTree(resp.body()).at("/embedding/values");
      double[] vec = new double[values.size()];
      for (int i = 0; i < vec.length; i++) {
        vec[i] = values.get(i).asDouble();
      }
      return vec;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String modelId() {
    return "gemini-embedding-001";
  }

  @Override
  public int dimensions() {
    return 768;
  }
}
