package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.example.domain.KnowledgeGraph;

/**
 * The durable "remember" pipeline — the Akka control plane that replaces Cognee's
 * relational-row run state + best-effort rollback ledger.
 *
 * <p>Two steps: (1) extract a graph with the LLM, (2) embed + persist to Fluree.
 * If a step fails it is retried and can resume after a crash, rather than
 * restarting from scratch (Cognee's behaviour). State is event-sourced.
 *
 * <p>The {@link MemoryEndpoint} also exposes a synchronous path for snappy UI
 * feedback; this workflow is the production-shaped, crash-safe version.
 */
@Component(id = "remember")
public class RememberWorkflow extends Workflow<RememberWorkflow.State> {

  public record State(String text, KnowledgeGraph graph, String commit, String status) {
    State withGraph(KnowledgeGraph g) {
      return new State(text, g, commit, "EXTRACTED");
    }

    State committed(String c) {
      return new State(text, graph, c, "COMMITTED");
    }
  }

  public record Start(String text) {}

  private final ComponentClient client;

  public RememberWorkflow(ComponentClient client) {
    this.client = client;
  }

  public Effect<Done> start(Start cmd) {
    if (currentState() != null) {
      return effects().error("already running");
    }
    return effects()
        .updateState(new State(cmd.text(), null, null, "STARTED"))
        .transitionTo(RememberWorkflow::extractStep)
        .withInput(cmd.text())
        .thenReply(Done.getInstance());
  }

  @StepName("extract")
  private StepEffect extractStep(String text) {
    KnowledgeGraph kg =
        client
            .forAgent()
            .inSession("remember-wf")
            .method(GraphExtractorAgent::extract)
            .invoke(text);
    return stepEffects()
        .updateState(currentState().withGraph(kg))
        .thenTransitionTo(RememberWorkflow::persistStep);
  }

  @StepName("persist")
  private StepEffect persistStep() {
    State s = currentState();
    double[] embedding = GeminiEmbeddings.embed(s.text());
    String commit = FlureeClient.remember(s.text(), s.graph(), embedding);
    return stepEffects().updateState(s.committed(commit)).thenEnd();
  }

  public ReadOnlyEffect<State> getState() {
    if (currentState() == null) {
      return effects().error("not found");
    }
    return effects().reply(currentState());
  }
}
