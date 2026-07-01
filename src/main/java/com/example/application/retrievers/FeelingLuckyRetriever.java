package com.example.application.retrievers;

import akka.javasdk.client.ComponentClient;
import com.example.application.SearchTypeClassifierAgent;
import com.example.application.SearchTypeClassifierAgent.Choice;
import java.util.UUID;

/**
 * Ask the classifier agent which retrieval strategy fits this question, then
 * delegate to that strategy. The wrapped answer reports its strategy as
 * {@code FEELING_LUCKY-&gt;X} so the UI can show what was picked.
 */
public final class FeelingLuckyRetriever implements Retriever {

  private final ComponentClient client;

  public FeelingLuckyRetriever(ComponentClient client) {
    this.client = client;
  }

  public Choice classify(String question) {
    try {
      var pick =
          client
              .forAgent()
              .inSession("classify-" + UUID.randomUUID())
              .method(SearchTypeClassifierAgent::classify)
              .invoke(question);
      return pick.strategy() == null ? Choice.RAG : pick.strategy();
    } catch (Exception e) {
      return Choice.RAG;
    }
  }

  @Override
  public Answer answer(String question) {
    Choice choice = classify(question);
    Retriever delegate =
        switch (choice) {
          case CHUNKS -> new ChunksRetriever();
          case GRAPH -> new GraphCompletionRetriever(client);
          case HYBRID -> new HybridRetriever(client);
          case LEXICAL -> new LexicalChunksRetriever();
          case RAG -> new RagCompletionRetriever(client);
        };
    Answer a = delegate.answer(question);
    return new Answer(a.text(), a.sources(), "FEELING_LUCKY->" + a.strategy());
  }
}
