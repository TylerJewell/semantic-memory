package com.example.application.retrievers;

import akka.javasdk.client.ComponentClient;
import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import com.example.application.QaAgent;
import java.util.List;

/**
 * Vanilla vector RAG: embed the question, cosine-search Fluree for the closest
 * chunks, then ask the Q&amp;A agent to answer from that text. This is the
 * original {@code MemoryEndpoint.recall} path lifted into the new retriever
 * interface so the endpoint can dispatch by strategy.
 */
public final class RagCompletionRetriever implements Retriever {

  private final ComponentClient client;

  public RagCompletionRetriever(ComponentClient client) {
    this.client = client;
  }

  @Override
  public Answer answer(String question) {
    double[] qv = GeminiEmbeddings.embed(question);
    List<String> chunks = FlureeClient.similar(qv, 3);
    String joined = String.join("\n---\n", chunks);
    String text =
        client
            .forAgent()
            .inSession("recall")
            .method(QaAgent::answer)
            .invoke(new QaAgent.Question(question, joined));
    return new Answer(text, chunks, Strategy.RAG_COMPLETION.name());
  }
}
