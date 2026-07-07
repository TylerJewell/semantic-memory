package com.example.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.application.FlureeClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Endpoint integration test for POST /api/sync. Extends the Akka {@link TestKitSupport} so it boots
 * the real service and drives it through the TestKit {@code httpClient}. The endpoint persists to
 * the live local Fluree (http://127.0.0.1:8090, ledger memory:main), which this test then queries
 * directly via {@link FlureeClient} to verify.
 *
 * <p>Idempotent across reruns: each run uses a unique nonce subject so assertions never collide with
 * a previous run's assertions.
 */
class SyncEndpointIT extends TestKitSupport {

  // Provide an explicit service name; otherwise the TestKit derives an empty gRPC
  // self-client name and builds the invalid config path
  // 'akka.javasdk.grpc.client..use-tls', which aborts startup under failsafe.
  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withServiceName("semantic-memory");
  }

  // EC-040: one authored fact + one corpus fact land in Fluree with the right layers.
  @Test
  void syncPersistsAuthoredAndDerivedFacts() {
    String subject = "syncperson" + System.nanoTime();

    var req =
        new MemoryEndpoint.SyncRequest(
            "vault/**",
            "corpus/**",
            List.of(new MemoryEndpoint.SyncFact(subject, "currentEmployer", "Acme", "vault/x.md", null)),
            List.of(new MemoryEndpoint.SyncFact(subject, "worksAt", "BigCo", "corpus/y.md", 0.8)),
            null,
            null);

    var response =
        httpClient
            .POST("/api/sync")
            .withRequestBody(req)
            .responseBodyAs(MemoryEndpoint.SyncResponse.class)
            .invoke();

    assertTrue(response.status().isSuccess(), "expected 2xx, got " + response.status());
    assertEquals(1, response.body().authored(), "one authored fact persisted");
    assertEquals(1, response.body().newCount(), "one derived fact ingested as NEW");

    List<FlureeClient.Envelope> authored = FlureeClient.queryEnvelopes(subject, "currentemployer");
    assertTrue(
        authored.stream()
            .anyMatch(
                e ->
                    e.object().equals("acme")
                        && e.layer().equals("authored")
                        && e.source().equals("vault/x.md")
                        && e.conf() == 1.0),
        "expected authored envelope {object=acme, layer=authored, source=vault/x.md, conf=1.0}; got "
            + authored);

    List<FlureeClient.Envelope> derived = FlureeClient.queryEnvelopes(subject, "worksat");
    assertTrue(
        derived.stream()
            .anyMatch(
                e ->
                    e.object().equals("bigco")
                        && e.layer().equals("derived")
                        && e.source().equals("corpus/y.md")),
        "expected derived envelope {object=bigco, layer=derived, source=corpus/y.md}; got " + derived);
  }

  // EC-043: overlapping globs -> 409; a corpus upload that copies a vault file -> 422.
  @Test
  void syncRejectsOverlapAndVaultLeak() {
    var overlap =
        new MemoryEndpoint.SyncRequest("data/**", "data/**", null, null, null, null);
    var overlapResp = httpClient.POST("/api/sync").withRequestBody(overlap).invoke();
    assertEquals(
        409, overlapResp.status().intValue(), "expected HTTP 409 for overlapping globs");

    String leaked = "the quick brown fox\njumps over the lazy dog";
    var leak =
        new MemoryEndpoint.SyncRequest(
            "vault/**",
            "corpus/**",
            null,
            null,
            List.of(new MemoryEndpoint.SyncFile("vault/a.md", leaked)),
            List.of(new MemoryEndpoint.SyncFile("corpus/a.md", leaked)));
    var leakResp = httpClient.POST("/api/sync").withRequestBody(leak).invoke();
    assertEquals(422, leakResp.status().intValue(), "expected HTTP 422 for vault leak");
  }
}
