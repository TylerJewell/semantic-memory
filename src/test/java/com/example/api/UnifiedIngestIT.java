package com.example.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.application.FlureeClient;
import com.example.domain.KnowledgeGraph;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * EC-051: a prose-sourced DERIVED assertion participates in the SAME reconciliation cascade as
 * authored/synced facts. Extends {@link TestKitSupport} so it boots the real service and drives it
 * through the TestKit {@code httpClient}; data persists to the live local Fluree (127.0.0.1:8090,
 * ledger memory:main).
 *
 * <p>The GraphExtractorAgent needs a live model that this test has no access to, so instead of
 * POSTing prose to /api/remember we seed the prose-derived assertion deterministically through the
 * SAME code path /api/remember uses — {@link FlureeClient#rememberAsProse} — with a hand-built
 * graph. That runs the real {@link com.example.application.ingest.IngestGate} and stamps a
 * {@code prose:*} source, exactly as the endpoint would after extraction.
 *
 * <p>Idempotent across reruns: a unique nonce subject means assertions never collide with a previous
 * run's.
 */
class UnifiedIngestIT extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withServiceName("semantic-memory");
  }

  @Test
  void proseDerivedAssertionReconcilesAgainstAuthoredFact() {
    String subject = "ec051person" + System.nanoTime();

    // 1. Authored fact via /api/sync: (subject, currentEmployer, Acme) from the vault.
    var syncReq =
        new MemoryEndpoint.SyncRequest(
            "vault/**",
            "corpus/**",
            List.of(
                new MemoryEndpoint.SyncFact(subject, "currentEmployer", "Acme", "vault/x.md", null)),
            null,
            null,
            null);
    var syncResp =
        httpClient
            .POST("/api/sync")
            .withRequestBody(syncReq)
            .responseBodyAs(MemoryEndpoint.SyncResponse.class)
            .invoke();
    assertTrue(syncResp.status().isSuccess(), "seed sync failed: " + syncResp.status());

    // 2. Prose-derived contradicting fact via the SAME path /api/remember uses. A hand-built graph
    //    stands in for the LLM extraction; rememberAsProse runs it through the ingest gate with a
    //    prose:* source.
    KnowledgeGraph kg =
        new KnowledgeGraph(
            List.of(),
            List.of(new KnowledgeGraph.Relationship(subject, "currentEmployer", "Globex")));
    FlureeClient.rememberAsProse("prose text about " + subject, kg);

    // 3. The prose-sourced derived fact must surface as a flagged loser under the authored Acme.
    var disResp =
        httpClient
            .GET("/api/disagreements")
            .responseBodyAs(MemoryEndpoint.DisagreementsResponse.class)
            .invoke();
    assertTrue(disResp.status().isSuccess(), "expected 2xx, got " + disResp.status());

    var match =
        disResp.body().disagreements().stream()
            .filter(d -> d.subject().equals(subject) && d.predicate().equals("currentemployer"))
            .findFirst();
    assertTrue(match.isPresent(), "expected a disagreement for (" + subject + ", currentemployer)");
    var d = match.get();
    assertTrue(d.served().equals("acme"), "expected served=acme, got " + d.served());
    assertTrue(
        d.flagged().stream()
            .anyMatch(f -> f.layer().equals("derived") && f.source().startsWith("prose:")),
        "expected a flagged derived fact with a prose:* source; got " + d.flagged());
  }
}
