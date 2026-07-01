package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpHelper {
  public static final String BASE = "http://localhost:9000";
  public static final ObjectMapper OM = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private HttpHelper() {}

  public static boolean serviceUp() {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(BASE + "/")).timeout(Duration.ofSeconds(2)).GET().build();
      HTTP.send(req, HttpResponse.BodyHandlers.discarding());
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static JsonNode postJson(String path, String body) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() >= 300) {
      throw new RuntimeException("POST " + path + " -> " + resp.statusCode() + ": " + resp.body());
    }
    return OM.readTree(resp.body());
  }

  public static JsonNode recall(String question, String strategy) throws Exception {
    String body = OM.writeValueAsString(new Recall(question, strategy));
    return postJson("/api/recall", body);
  }

  public static void remember(String text) throws Exception {
    postJson("/api/remember", OM.writeValueAsString(new Remember(text)));
  }

  record Recall(String question, String strategy) {}

  record Remember(String text) {}
}
