package com.example.application.retrievers;

import com.example.application.FlureeClient;
import java.util.List;

/**
 * BM25 keyword search via Fluree full-text index. Degrades to empty list if
 * the index is not configured. Mirrors cognee's lexical_retriever (no LLM).
 */
public final class LexicalChunksRetriever implements Retriever {

  private final int limit;

  public LexicalChunksRetriever() {
    this(5);
  }

  public LexicalChunksRetriever(int limit) {
    this.limit = limit;
  }

  @Override
  public Answer answer(String question) {
    List<String> chunks = FlureeClient.bm25Search(question, limit);
    return new Answer(String.join("\n---\n", chunks), chunks, Strategy.LEXICAL.name());
  }
}
