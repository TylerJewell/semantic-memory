package com.example.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * EC-054: the observe surface (/api/stats, /api/recent) reads the provenanced {@code ex:Assertion}
 * model. Extends {@link TestKitSupport} so it boots the real service and drives it through the
 * TestKit {@code httpClient}; data persists to the live local Fluree (127.0.0.1:8090, ledger
 * memory:main).
 *
 * <p>Idempotent across reruns: unique nonce subjects mean fresh assertions never collide with a
 * previous run's, so the before/after count delta is stable.
 */
class ObserveIT extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withServiceName("semantic-memory");
  }

  @Test
  void statsAndRecentReflectAssertionModel() {
    long nonce = System.nanoTime();
    String subjectA = "ec054a" + nonce;
    String subjectB = "ec054b" + nonce;

    var before =
        httpClient
            .GET("/api/stats")
            .responseBodyAs(MemoryEndpoint.StatsResponse.class)
            .invoke();
    assertTrue(before.status().isSuccess(), "expected 2xx, got " + before.status());
    int assertionsBefore = before.body().chunks();

    // Seed two fresh authored assertions (distinct subjects) via /api/sync.
    var syncReq =
        new MemoryEndpoint.SyncRequest(
            "vault/**",
            "corpus/**",
            List.of(
                new MemoryEndpoint.SyncFact(subjectA, "homeCity", "Portland", "vault/a.md", null),
                new MemoryEndpoint.SyncFact(subjectB, "homeCity", "Seattle", "vault/b.md", null)),
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

    var after =
        httpClient
            .GET("/api/stats")
            .responseBodyAs(MemoryEndpoint.StatsResponse.class)
            .invoke();
    assertTrue(after.status().isSuccess(), "expected 2xx, got " + after.status());
    assertTrue(
        after.body().chunks() >= assertionsBefore + 2,
        "expected assertion count to grow by >=2 (from "
            + assertionsBefore
            + "); got "
            + after.body().chunks());
    assertTrue(after.body().entities() > 0, "expected distinct-subject count > 0");

    var recent =
        httpClient
            .GET("/api/recent")
            .responseBodyAs(MemoryEndpoint.RecentResponse.class)
            .invoke();
    assertTrue(recent.status().isSuccess(), "expected 2xx, got " + recent.status());
    assertFalse(recent.body().chunks().isEmpty(), "expected /api/recent to return facts");
  }
}
