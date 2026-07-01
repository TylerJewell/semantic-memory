package com.example.application.retrievers;

import java.util.List;

/**
 * One retrieval strategy. Implementations turn a natural-language question into
 * an {@link Answer} — either a free-text completion grounded in retrieved
 * context, or just the raw context (for chunk/lexical retrievers that skip the
 * LLM, mirroring Cognee's BaseRetriever.get_completion_from_context contract).
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
   * @param text the answer (or, for non-LLM retrievers, the joined chunk text)
   * @param sources the raw context fragments used to ground the answer
   * @param strategy the strategy that actually produced this answer (FeelingLucky
   *     replaces itself with the strategy it delegated to)
   */
  record Answer(String text, List<String> sources, String strategy) {}

  Answer answer(String question);
}
