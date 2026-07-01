package com.example.application.retrievers;

import com.example.application.FlureeClient;
import java.util.List;

/**
 * BM25 keyword search via Fluree full-text index. No LLM call. Degrades to an
 * empty list if the BM25 index has not been created on the ledger.
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
    List<Source> sources = chunks.stream().map(Source::unscored).toList();
    return new Answer(String.join("\n---\n", chunks), sources, Strategy.LEXICAL.name());
  }
}
