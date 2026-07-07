package com.example.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Endpoint integration test for GET /api/fact (EC-052) — the fact store's primary read: "what is the
 * served value for subject + predicate?". Extends {@link TestKitSupport} so it boots the real service
 * and drives it through the TestKit {@code httpClient}; the endpoint reads the live local Fluree
 * (http://127.0.0.1:8090, ledger memory:main) that POST /api/sync seeds.
 *
 * <p>Idempotent across reruns: each run uses unique nonce subjects so assertions never collide.
 */
class FactLookupIT extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withServiceName("semantic-memory");
  }

  // EC-052: authored value is served over a flagged derived value (Model 1).
  @Test
  void resolvedServesAuthoredOverDerived() {
    String subject = "factperson" + System.nanoTime();

    var seed =
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
    var seedResp = httpClient.POST("/api/sync").withRequestBody(seed).invoke();
    assertTrue(seedResp.status().isSuccess(), "seed expected 2xx, got " + seedResp.status());

    var response =
        httpClient
            .GET("/api/fact?subject=" + subject + "&predicate=currentEmployer")
            .responseBodyAs(MemoryEndpoint.FactResponse.class)
            .invoke();

    assertTrue(response.status().isSuccess(), "expected 2xx, got " + response.status());
    var fact = response.body();
    assertEquals("RESOLVED", fact.state(), "authored value should be served: " + fact);
    assertEquals("acme", fact.served(), "served value should be the slugged authored object");
    assertEquals("authored", fact.servedLayer(), "authored layer is served under Model 1");
    assertTrue(fact.flaggedCount() >= 1, "the derived value should be flagged: " + fact);
  }

  // EC-052: two authored values on a functional predicate contest with no winner.
  @Test
  void contestedOnAuthoredTie() {
    String subject = "factcity" + System.nanoTime();

    var seed =
        new MemoryEndpoint.SyncRequest(
            "vault/**",
            "corpus/**",
            List.of(
                new MemoryEndpoint.SyncFact(subject, "homeCity", "Portland", "vault/a.md", null),
                new MemoryEndpoint.SyncFact(subject, "homeCity", "Seattle", "vault/b.md", null)),
            null,
            null,
            null);
    var seedResp = httpClient.POST("/api/sync").withRequestBody(seed).invoke();
    assertTrue(seedResp.status().isSuccess(), "seed expected 2xx, got " + seedResp.status());

    var response =
        httpClient
            .GET("/api/fact?subject=" + subject + "&predicate=homeCity")
            .responseBodyAs(MemoryEndpoint.FactResponse.class)
            .invoke();

    assertTrue(response.status().isSuccess(), "expected 2xx, got " + response.status());
    var fact = response.body();
    assertEquals("CONTESTED", fact.state(), "two authored values should contest: " + fact);
    assertEquals("authored-tie", fact.reason(), "terminal cascade reason for authored tie");
    assertNull(fact.served(), "a contested fact has no served value");
    assertNotNull(fact.candidates(), "contested candidates should be surfaced");
    assertEquals(2, fact.candidates().size(), "both authored candidates surfaced: " + fact);
  }

  // EC-052: a never-seen subject is UNKNOWN, not an error.
  @Test
  void unknownForNeverSeenSubject() {
    String subject = "factghost" + System.nanoTime();

    var response =
        httpClient
            .GET("/api/fact?subject=" + subject + "&predicate=currentEmployer")
            .responseBodyAs(MemoryEndpoint.FactResponse.class)
            .invoke();

    assertTrue(response.status().isSuccess(), "expected 2xx, got " + response.status());
    var fact = response.body();
    assertEquals("UNKNOWN", fact.state(), "never-seen subject should be UNKNOWN: " + fact);
    assertNull(fact.served(), "UNKNOWN has no served value");
  }
}
