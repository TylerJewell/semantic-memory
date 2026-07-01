package com.example.application.retrievers;

import java.util.List;

/**
 * One retrieval strategy. Implementations turn a natural-language question into
 * an {@link Answer} — either a free-text completion grounded in retrieved
 * context, or just the raw context (for chunk/lexical retrievers that skip the
 * LLM).
 */
public interface Retriever {

  /** Available strategies, exposed over the HTTP API. */
  enum Strategy {
    CHUNKS,
    RAG_COMPLETION,
    GRAPH_COMPLETION,
    HYBRID,
    LEXICAL,
    FEELING_LUCKY
  }

  /**
   * One item of retrieved evidence. {@code score} is populated by vector /
   * lexical searches (higher = more similar) and is {@code null} for graph-walk
   * derived triples that don't carry a numeric ranking.
   */
  record Source(String text, Double score) {
    public static Source unscored(String text) {
      return new Source(text, null);
    }
  }

  /**
   * @param text the answer (or, for non-LLM retrievers, the joined chunk text)
   * @param sources the ranked evidence used to ground the answer
   * @param strategy the strategy that actually produced this answer (FeelingLucky
   *     replaces itself with the strategy it delegated to)
   */
  record Answer(String text, List<Source> sources, String strategy) {}

  Answer answer(String question);
}
