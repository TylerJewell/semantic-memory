package com.example.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Endpoint integration test for the read side: GET /api/disagreements and GET /api/conflicts.
 * Extends {@link TestKitSupport} so it boots the real service and drives it through the TestKit
 * {@code httpClient}. Data is seeded by POSTing to /api/sync (which persists to the live local
 * Fluree at http://127.0.0.1:8090, ledger memory:main); the GET endpoints then reconcile it.
 *
 * <p>Idempotent across reruns: each run uses unique nonce subjects so assertions never collide with
 * a previous run's assertions (Fluree persists across runs).
 */
class SurfaceIT extends TestKitSupport {

  // Provide an explicit service name; otherwise the TestKit derives an empty gRPC
  // self-client name and builds the invalid config path
  // 'akka.javasdk.grpc.client..use-tls', which aborts startup under failsafe.
  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withServiceName("semantic-memory");
  }

  // EC-041: authored (Acme) vs derived (Globex) on a functional predicate ->
  // a cross-layer DISAGREEMENT (served=authored, flagged=derived), NOT a conflict.
  @Test
  void authoredOverDerivedSurfacesAsDisagreement() {
    String subject = "ec041person" + System.nanoTime();

    var req =
        new MemoryEndpoint.SyncRequest(
            "vault/**",
            "corpus/**",
            List.of(
                new MemoryEndpoint.SyncFact(subject, "currentEmployer", "Acme", "vault/x.md", null)),
            List.of(
                new MemoryEndpoint.SyncFact(
                    subject, "currentEmployer", "Globex", "corpus/y.md", 0.9)),
            null,
            null);

    var syncResp =
        httpClient
            .POST("/api/sync")
            .withRequestBody(req)
            .responseBodyAs(MemoryEndpoint.SyncResponse.class)
            .invoke();
    assertTrue(syncResp.status().isSuccess(), "seed sync failed: " + syncResp.status());

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
    assertTrue(d.servedLayer().equals("authored"), "expected servedLayer=authored");
    assertTrue(
        d.flagged().stream()
            .anyMatch(f -> f.object().equals("globex") && f.layer().equals("derived")),
        "expected flagged to contain the derived globex object; got " + d.flagged());

    var confResp =
        httpClient
            .GET("/api/conflicts")
            .responseBodyAs(MemoryEndpoint.ConflictsResponse.class)
            .invoke();
    assertTrue(confResp.status().isSuccess(), "expected 2xx, got " + confResp.status());
    assertFalse(
        confResp.body().conflicts().stream()
            .anyMatch(
                c -> c.subject().equals(subject) && c.predicate().equals("currentemployer")),
        "cross-layer disagreement must NOT appear as a conflict");
  }

  // EC-042: two authored values of equal priority on a functional predicate ->
  // a CONFLICT with reason "authored-tie".
  @Test
  void twoAuthoredValuesSurfaceAsAuthoredTieConflict() {
    String subject = "ec042person" + System.nanoTime();

    var req =
        new MemoryEndpoint.SyncRequest(
            "vault/**",
            "corpus/**",
            List.of(
                new MemoryEndpoint.SyncFact(subject, "homeCity", "Portland", "vault/a.md", null),
                new MemoryEndpoint.SyncFact(subject, "homeCity", "Seattle", "vault/b.md", null)),
            null,
            null,
            null);

    var syncResp =
        httpClient
            .POST("/api/sync")
            .withRequestBody(req)
            .responseBodyAs(MemoryEndpoint.SyncResponse.class)
            .invoke();
    assertTrue(syncResp.status().isSuccess(), "seed sync failed: " + syncResp.status());

    var confResp =
        httpClient
            .GET("/api/conflicts")
            .responseBodyAs(MemoryEndpoint.ConflictsResponse.class)
            .invoke();
    assertTrue(confResp.status().isSuccess(), "expected 2xx, got " + confResp.status());

    var match =
        confResp.body().conflicts().stream()
            .filter(c -> c.subject().equals(subject) && c.predicate().equals("homecity"))
            .findFirst();
    assertTrue(match.isPresent(), "expected a conflict for (" + subject + ", homecity)");
    var c = match.get();
    assertTrue(c.reason().equals("authored-tie"), "expected reason=authored-tie, got " + c.reason());
    assertTrue(
        c.candidates().stream().anyMatch(f -> f.object().equals("portland"))
            && c.candidates().stream().anyMatch(f -> f.object().equals("seattle")),
        "expected candidates to contain portland and seattle; got " + c.candidates());
  }
}
