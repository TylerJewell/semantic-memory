package com.example.application.retrievers;

import akka.javasdk.client.ComponentClient;
import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import com.example.application.QaAgent;
import java.util.ArrayList;
import java.util.List;

/**
 * Vector top-k → one-hop entity neighborhood for those chunks → triples + chunks
 * → QaAgent answer. The vector hits carry cosine scores; the graph-walked triples
 * are unscored.
 */
public final class GraphCompletionRetriever implements Retriever {

  private final ComponentClient client;

  public GraphCompletionRetriever(ComponentClient client) {
    this.client = client;
  }

  @Override
  public Answer answer(String question) {
    double[] qv = GeminiEmbeddings.embed(question);
    List<FlureeClient.Hit> hits = FlureeClient.similarWithScores(qv, 3);
    List<String> chunks = hits.stream().map(FlureeClient.Hit::text).toList();
    List<String> triples = FlureeClient.entityNeighborhood(chunks, 25);

    StringBuilder ctx = new StringBuilder();
    if (!triples.isEmpty()) {
      ctx.append("Graph triples:\n").append(String.join("\n", triples)).append("\n\n");
    }
    if (!chunks.isEmpty()) {
      ctx.append("Chunks:\n").append(String.join("\n---\n", chunks));
    }

    String text = client
        .forAgent()
        .inSession("recall")
        .method(QaAgent::answer)
        .invoke(new QaAgent.Question(question, ctx.toString()));

    List<Source> sources = new ArrayList<>();
    triples.forEach(t -> sources.add(Source.unscored(t)));
    hits.forEach(h -> sources.add(new Source(h.text(), h.score())));
    return new Answer(text, sources, Strategy.GRAPH_COMPLETION.name());
  }
}
