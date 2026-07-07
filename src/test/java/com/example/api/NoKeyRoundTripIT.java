package com.example.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.application.FlureeClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * EC-001 — the no-key, zero-external-network round-trip.
 *
 * <p>Proves the full ingest path runs with NO API key: POST /api/remember extracts a knowledge
 * graph using the in-JVM Jlama LLM (selected by {@code ModelSelector.chatProvider()} because
 * {@code GOOGLE_AI_GEMINI_API_KEY} is absent) and persists provenanced assertions into the local
 * Fluree at 127.0.0.1:8090. No googleapis.com (or any external host) is contacted on this path.
 *
 * <p>First run downloads the ~700MB model into {@code ./models} and runs real CPU inference, so it
 * is slow — hence the generous {@link Timeout} and the {@code live} tag (opt-in under failsafe).
 */
@Tag("live")
class NoKeyRoundTripIT extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withServiceName("semantic-memory");
  }

  @Test
  @Timeout(600) // seconds — model download + CPU inference on first run
  void remembersProseWithNoApiKeyUsingLocalModel() {
    String key = System.getenv("GOOGLE_AI_GEMINI_API_KEY");
    Assumptions.assumeTrue(
        key == null || key.isBlank(),
        "GOOGLE_AI_GEMINI_API_KEY is set; EC-001 asserts the no-key (local Jlama) path — skipping.");

    // Baseline assertion count so we can prove at least one NEW assertion was persisted.
    int before = FlureeClient.assertionStats().assertions();

    var req = new MemoryEndpoint.RememberRequest("Tyler works at Acme.");
    var resp =
        httpClient
            .POST("/api/remember")
            .withRequestBody(req)
            .responseBodyAs(MemoryEndpoint.RememberResponse.class)
            .invoke();

    // (a) /remember returned 200 using the LOCAL model (no key).
    assertTrue(resp.status().isSuccess(), "expected 2xx from /api/remember, got " + resp.status());

    var body = resp.body();
    // If the tiny model extracted nothing, surface the raw graph — that is a real finding about the
    // local-model quality floor, not something to paper over.
    assertTrue(
        body.graph() != null
            && body.graph().relationships() != null
            && !body.graph().relationships().isEmpty(),
        "TinyLlama extracted no relationships from 'Tyler works at Acme.' — raw graph: "
            + body.graph()
            + " report: "
            + body.report());

    // (b) at least one provenanced assertion was persisted from the prose.
    int after = FlureeClient.assertionStats().assertions();
    assertTrue(
        after > before,
        "expected at least one new assertion persisted (before="
            + before
            + ", after="
            + after
            + "); recent="
            + FlureeClient.recentAssertions(10)
            + " graph="
            + body.graph());
  }
}
