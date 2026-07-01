package com.example.application.retrievers;

import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import java.util.List;

/** Top-k vector chunks, no LLM. Mirrors Cognee's chunks_retriever. */
public final class ChunksRetriever implements Retriever {

  private final int limit;

  public ChunksRetriever() {
    this(5);
  }

  public ChunksRetriever(int limit) {
    this.limit = limit;
  }

  @Override
  public Answer answer(String question) {
    double[] qv = GeminiEmbeddings.embed(question);
    List<String> chunks = FlureeClient.similar(qv, limit);
    return new Answer(String.join("\n---\n", chunks), chunks, Strategy.CHUNKS.name());
  }
}
