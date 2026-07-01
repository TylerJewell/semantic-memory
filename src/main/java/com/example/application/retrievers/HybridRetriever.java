package com.example.application.retrievers;

import akka.javasdk.client.ComponentClient;
import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import com.example.application.QaAgent;
import java.util.ArrayList;
import java.util.List;

/**
 * Combine vector chunks + graph neighborhood into one context, then answer.
 * Mirrors cognee/modules/retrieval/hybrid_retriever.py.
 */
public final class HybridRetriever implements Retriever {

  private final ComponentClient client;

  public HybridRetriever(ComponentClient client) {
    this.client = client;
  }

  @Override
  public Answer answer(String question) {
    double[] qv = GeminiEmbeddings.embed(question);
    List<String> chunks = FlureeClient.similar(qv, 5);
    List<String> triples = FlureeClient.entityNeighborhood(chunks, 25);

    StringBuilder ctx = new StringBuilder();
    if (!chunks.isEmpty()) {
      ctx.append("Chunks:\n").append(String.join("\n---\n", chunks)).append("\n\n");
    }
    if (!triples.isEmpty()) {
      ctx.append("Graph triples:\n").append(String.join("\n", triples));
    }

    String text =
        client
            .forAgent()
            .inSession("recall")
            .method(QaAgent::answer)
            .invoke(new QaAgent.Question(question, ctx.toString()));

    List<String> sources = new ArrayList<>();
    sources.addAll(chunks);
    sources.addAll(triples);
    return new Answer(text, sources, Strategy.HYBRID.name());
  }
}
