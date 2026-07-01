package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;

/**
 * Classifies a question into one of the supported retrieval strategies. Powers
 * the FEELING_LUCKY retriever's "let the system pick" mode.
 */
@Component(id = "search-type-classifier")
public class SearchTypeClassifierAgent extends Agent {

  public enum Choice {
    CHUNKS,
    RAG,
    GRAPH,
    HYBRID,
    LEXICAL
  }

  public record Pick(Choice strategy) {}

  private static final String SYSTEM_MESSAGE =
      """
      You are an expert query analyzer for a GraphRAG system. Pick the single
      best retrieval strategy for the user's question from:
      - CHUNKS: return raw matching text snippets, no synthesis. Use when the
        user explicitly asks for raw text/snippets/passages.
      - RAG: vector retrieval + LLM answer. Use for direct factual questions
        likely answered by a specific passage.
      - GRAPH: graph traversal + LLM answer. Use when relationships between
        entities matter or the question asks "how is X related to Y".
      - HYBRID: combines vector + graph. Use for complex questions that
        benefit from both passages and relationships.
      - LEXICAL: BM25 keyword search, no synthesis. Use when the user mentions
        keywords or exact-match search.

      Return only the chosen strategy.
      """
          .stripIndent();

  public Effect<Pick> classify(String question) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(question)
        .responseConformsTo(Pick.class)
        .thenReply();
  }
}
