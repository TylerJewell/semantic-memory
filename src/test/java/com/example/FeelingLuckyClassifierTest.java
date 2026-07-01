package com.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Drives the classifier through the live /api/recall?strategy=FEELING_LUCKY
 * path and inspects the strategy tag in the response (FEELING_LUCKY->X).
 * LLM classification is stochastic — we accept any reasonable choice from a set.
 */
public class FeelingLuckyClassifierTest {

  @BeforeAll
  static void up() throws Exception {
    assumeTrue(HttpHelper.serviceUp(), "Akka service must be running on :9000");
    HttpHelper.remember("Akka provides Agents, Workflows, and Entities.");
  }

  private static String pickedFor(String question) throws Exception {
    JsonNode r = HttpHelper.recall(question, "FEELING_LUCKY");
    String s = r.path("strategy").asText("");
    assertThat(s).startsWith("FEELING_LUCKY->");
    return s.substring("FEELING_LUCKY->".length());
  }

  @Test
  void rawTextQuestionPicksChunksOrLexical() throws Exception {
    String pick = pickedFor("give me the raw text about Akka");
    assertThat(pick).isIn("CHUNKS", "LEXICAL", "RAG_COMPLETION");
  }

  @Test
  void relationalQuestionPicksGraphOrRagOrHybrid() throws Exception {
    String pick = pickedFor("What's in Akka and how are its parts related?");
    assertThat(pick).isIn("GRAPH_COMPLETION", "RAG_COMPLETION", "HYBRID");
  }
}
