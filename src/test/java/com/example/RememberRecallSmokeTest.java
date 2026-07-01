package com.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class RememberRecallSmokeTest {

  private static final String FACT = "Akka provides Agents, Workflows, and Entities.";
  private static final String Q = "What does Akka provide?";

  @BeforeAll
  static void ingest() throws Exception {
    assumeTrue(HttpHelper.serviceUp(), "Akka service must be running on :9000");
    HttpHelper.remember(FACT);
  }

  private static void assertRecall(String strategy, boolean expectAnswer) throws Exception {
    JsonNode r = HttpHelper.recall(Q, strategy);
    String answer = r.path("answer").asText("");
    JsonNode ctx = r.path("context");
    String ctxAll = ctx.toString().toLowerCase();
    if (expectAnswer) {
      assertThat(answer).as("answer for " + strategy).isNotBlank();
    }
    if (strategy.equals("LEXICAL")) {
      assumeTrue(ctx.size() > 0, "LEXICAL: BM25 index not configured, skipping");
    }
    assertThat(ctxAll).as("context for " + strategy + " contains Akka").contains("akka");
  }

  @Test
  void chunks() throws Exception {
    JsonNode r = HttpHelper.recall(Q, "CHUNKS");
    assertThat(r.path("context").toString().toLowerCase()).contains("akka");
  }

  @Test
  void rag() throws Exception {
    assertRecall("RAG", true);
  }

  @Test
  void graph() throws Exception {
    assertRecall("GRAPH", true);
  }

  @Test
  void hybrid() throws Exception {
    assertRecall("HYBRID", true);
  }

  @Test
  void feelingLucky() throws Exception {
    JsonNode r = HttpHelper.recall(Q, "FEELING_LUCKY");
    assertThat(r.path("answer").asText("")).isNotBlank();
    assertThat(r.path("strategy").asText("")).startsWith("FEELING_LUCKY->");
  }

  @Test
  void lexical() throws Exception {
    JsonNode r = HttpHelper.recall(Q, "LEXICAL");
    assumeTrue(r.path("context").size() > 0, "BM25 not configured");
    assertThat(r.path("context").toString().toLowerCase()).contains("akka");
  }
}
