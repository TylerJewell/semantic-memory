package com.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class GraphNeighborhoodTest {

  @BeforeAll
  static void ingest() throws Exception {
    assumeTrue(HttpHelper.serviceUp(), "Akka service must be running on :9000");
    HttpHelper.remember("Akka provides Agents, Workflows, and Entities.");
  }

  @Test
  void graphAnswerMentionsAnAkkaConcept() throws Exception {
    JsonNode r = HttpHelper.recall("What does Akka provide?", "GRAPH");
    String answer = r.path("answer").asText("").toLowerCase();
    assertThat(answer).isNotBlank();
    assertThat(answer)
        .as("graph answer should mention one of agents/workflows/entities")
        .containsAnyOf("agent", "workflow", "entit");
  }
}
