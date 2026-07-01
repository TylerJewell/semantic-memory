package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.example.application.FlureeClient;
import com.example.application.GeminiEmbeddings;
import com.example.application.GraphExtractorAgent;
import com.example.application.retrievers.ChunksRetriever;
import com.example.application.retrievers.FeelingLuckyRetriever;
import com.example.application.retrievers.GraphCompletionRetriever;
import com.example.application.retrievers.HybridRetriever;
import com.example.application.retrievers.LexicalChunksRetriever;
import com.example.application.retrievers.RagCompletionRetriever;
import com.example.application.retrievers.Retriever;
import com.example.domain.KnowledgeGraph;
import java.util.ArrayList;
import java.util.List;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/api")
public class MemoryEndpoint {

  private static final List<String> COMPARE_STRATEGIES =
      List.of("CHUNKS", "RAG", "GRAPH", "HYBRID", "FEELING_LUCKY");

  private final ComponentClient client;

  public MemoryEndpoint(ComponentClient client) {
    this.client = client;
  }

  public record RememberRequest(String text) {}
  public record RememberResponse(KnowledgeGraph graph, String commit) {}
  public record RecallRequest(String question, String strategy) {}
  public record RecallResponse(String answer, List<Retriever.Source> context, String strategy) {}
  public record StatsResponse(int chunks, int entities, int commits) {}
  public record RecentResponse(List<String> chunks) {}
  public record CompareRequest(String question) {}
  public record CompareResult(String strategy, String answer, long ms) {}
  public record CompareResponse(String question, List<CompareResult> results) {}
  public record ForgetResponse(String status) {}

  @Post("/remember")
  public RememberResponse remember(RememberRequest req) {
    KnowledgeGraph kg = client
        .forAgent()
        .inSession("remember")
        .method(GraphExtractorAgent::extract)
        .invoke(req.text());
    double[] embedding = GeminiEmbeddings.embed(req.text());
    String commit = FlureeClient.remember(req.text(), kg, embedding);
    return new RememberResponse(kg, commit);
  }

  @Post("/recall")
  public RecallResponse recall(RecallRequest req) {
    String strategy = req.strategy() == null ? "RAG" : req.strategy().trim().toUpperCase();
    Retriever r = retrieverFor(strategy);
    Retriever.Answer a = r.answer(req.question());
    return new RecallResponse(a.text(), a.sources(), a.strategy());
  }

  @Get("/stats")
  public StatsResponse stats() {
    FlureeClient.Stats s = FlureeClient.stats();
    return new StatsResponse(s.chunks(), s.entities(), s.commits());
  }

  @Get("/recent")
  public RecentResponse recent() {
    return new RecentResponse(FlureeClient.recentChunks(5));
  }

  @Post("/compare")
  public CompareResponse compare(CompareRequest req) {
    List<CompareResult> results = new ArrayList<>();
    for (String strat : COMPARE_STRATEGIES) {
      long t0 = System.currentTimeMillis();
      String answer;
      try {
        Retriever r = retrieverFor(strat);
        answer = r.answer(req.question()).text();
      } catch (Exception e) {
        answer = "(error: " + e.getMessage() + ")";
      }
      results.add(new CompareResult(strat, answer, System.currentTimeMillis() - t0));
    }
    return new CompareResponse(req.question(), results);
  }

  @Post("/forget")
  public ForgetResponse forget() {
    FlureeClient.forgetAll();
    return new ForgetResponse("cleared");
  }

  private Retriever retrieverFor(String s) {
    return switch (s) {
      case "CHUNKS" -> new ChunksRetriever();
      case "GRAPH", "GRAPH_COMPLETION" -> new GraphCompletionRetriever(client);
      case "HYBRID" -> new HybridRetriever(client);
      case "LEXICAL" -> new LexicalChunksRetriever();
      case "FEELING_LUCKY" -> new FeelingLuckyRetriever(client);
      case "RAG", "RAG_COMPLETION" -> new RagCompletionRetriever(client);
      default -> new RagCompletionRetriever(client);
    };
  }
}
