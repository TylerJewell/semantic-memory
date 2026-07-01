package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import com.example.domain.KnowledgeGraph;

/**
 * The "smart reader" — the LLM step that turns raw text into structured graph.
 *
 * <p>Reads a chunk of text and returns a typed {@link KnowledgeGraph}. The
 * {@code responseConformsTo} call makes the Akka SDK generate a JSON schema from
 * the record and validate the model's reply against it — no external
 * structured-output library needed; LangChain4j's schema enforcement does the
 * work under the hood.
 */
@Component(id = "graph-extractor")
public class GraphExtractorAgent extends Agent {

  private static final String SYSTEM_MESSAGE =
      """
      You extract a knowledge graph from the given text.
      Identify the key entities and the directed relationships between them.
      Rules:
      - Only include entities and relationships explicitly supported by the text.
      - Use concise, canonical entity names (no trailing punctuation).
      - Every relationship's source and target MUST exactly match an entity name you list.
      - Prefer specific relationship verbs (e.g. "provides", "is part of", "runs on").
      """
          .stripIndent();

  public Effect<KnowledgeGraph> extract(String text) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(text)
        .responseConformsTo(KnowledgeGraph.class)
        .thenReply();
  }
}
