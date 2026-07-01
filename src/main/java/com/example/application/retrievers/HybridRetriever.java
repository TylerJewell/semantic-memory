package com.example.application.retrievers;

import akka.javasdk.client.ComponentClient;
import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import com.example.application.QaAgent;
import java.util.ArrayList;
import java.util.List;

/** Vector chunks + graph neighborhood combined into a single answering context. */
public final class HybridRetriever implements Retriever {

  private final ComponentClient client;

  public HybridRetriever(ComponentClient client) {
    this.client = client;
  }

  @Override
  public Answer answer(String question) {
    double[] qv = GeminiEmbeddings.embed(question);
    List<FlureeClient.Hit> hits = FlureeClient.similarWithScores(qv, 5);
    List<String> chunks = hits.stream().map(FlureeClient.Hit::text).toList();
    List<String> triples = FlureeClient.entityNeighborhood(chunks, 25);

    StringBuilder ctx = new StringBuilder();
    if (!chunks.isEmpty()) {
      ctx.append("Chunks:\n").append(String.join("\n---\n", chunks)).append("\n\n");
    }
    if (!triples.isEmpty()) {
      ctx.append("Graph triples:\n").append(String.join("\n", triples));
    }

    String text = client
        .forAgent()
        .inSession("recall")
        .method(QaAgent::answer)
        .invoke(new QaAgent.Question(question, ctx.toString()));

    List<Source> sources = new ArrayList<>();
    hits.forEach(h -> sources.add(new Source(h.text(), h.score())));
    triples.forEach(t -> sources.add(Source.unscored(t)));
    return new Answer(text, sources, Strategy.HYBRID.name());
  }
}
