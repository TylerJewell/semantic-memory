package com.example.application.retrievers;

import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import java.util.List;

/** Top-k vector chunks. No LLM call. */
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
    List<FlureeClient.Hit> hits = FlureeClient.similarWithScores(qv, limit);
    List<Source> sources = hits.stream().map(h -> new Source(h.text(), h.score())).toList();
    String joined = String.join("\n---\n", hits.stream().map(FlureeClient.Hit::text).toList());
    return new Answer(joined, sources, Strategy.CHUNKS.name());
  }
}
