package com.example.application.retrievers;

import akka.javasdk.client.ComponentClient;
import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import com.example.application.QaAgent;
import java.util.ArrayList;
import java.util.List;

/**
 * Vector top-k → one-hop entity neighborhood for those chunks → triples + chunks
 * → QaAgent answer. Mirrors cognee/modules/retrieval/graph_completion_retriever.py.
 */
public final class GraphCompletionRetriever implements Retriever {

  private final ComponentClient client;

  public GraphCompletionRetriever(ComponentClient client) {
    this.client = client;
  }

  @Override
  public Answer answer(String question) {
    double[] qv = GeminiEmbeddings.embed(question);
    List<String> chunks = FlureeClient.similar(qv, 3);
    List<String> triples = FlureeClient.entityNeighborhood(chunks, 25);

    StringBuilder ctx = new StringBuilder();
    if (!triples.isEmpty()) {
      ctx.append("Graph triples:\n").append(String.join("\n", triples)).append("\n\n");
    }
    if (!chunks.isEmpty()) {
      ctx.append("Chunks:\n").append(String.join("\n---\n", chunks));
    }

    String text =
        client
            .forAgent()
            .inSession("recall")
            .method(QaAgent::answer)
            .invoke(new QaAgent.Question(question, ctx.toString()));

    List<String> sources = new ArrayList<>();
    sources.addAll(triples);
    sources.addAll(chunks);
    return new Answer(text, sources, Strategy.GRAPH_COMPLETION.name());
  }
}
