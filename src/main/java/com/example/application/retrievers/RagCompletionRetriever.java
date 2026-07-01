package com.example.application.retrievers;

import akka.javasdk.client.ComponentClient;
import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import com.example.application.QaAgent;
import java.util.List;

/**
 * Vector RAG: embed the question, cosine-search Fluree for the closest chunks,
 * ask the Q&amp;A agent to answer from the joined text. Scores are the cosine
 * similarities returned by Fluree, threaded through to the UI.
 */
public final class RagCompletionRetriever implements Retriever {

  private static final int TOP_K = 3;
  private final ComponentClient client;

  public RagCompletionRetriever(ComponentClient client) {
    this.client = client;
  }

  @Override
  public Answer answer(String question) {
    double[] qv = GeminiEmbeddings.embed(question);
    List<FlureeClient.Hit> hits = FlureeClient.similarWithScores(qv, TOP_K);
    List<Source> sources = hits.stream().map(h -> new Source(h.text(), h.score())).toList();
    String joined = String.join("\n---\n", hits.stream().map(FlureeClient.Hit::text).toList());
    String text = client
        .forAgent()
        .inSession("recall")
        .method(QaAgent::answer)
        .invoke(new QaAgent.Question(question, joined));
    return new Answer(text, sources, Strategy.RAG_COMPLETION.name());
  }
}
