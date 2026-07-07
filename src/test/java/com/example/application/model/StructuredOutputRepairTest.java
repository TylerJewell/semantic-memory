package com.example.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** EC-005: structured-output repair loop — bounded retries, then a reduced-schema fallback. */
public class StructuredOutputRepairTest {

  private record Parsed(String value) {}

  /** Accepts only replies that start with "{ok"; anything else is a schema violation. */
  private static StructuredOutputRepair.Validator<Parsed> validator(AtomicInteger calls) {
    return raw -> {
      calls.incrementAndGet();
      if (raw == null || !raw.startsWith("{ok")) {
        throw new IllegalArgumentException("schema violation: " + raw);
      }
      return new Parsed(raw);
    };
  }

  // EC-005: malformed for the first 2 calls, then valid -> no fallback, bounded, parsed value.
  @Test
  public void boundedThenEventuallyValid() {
    int maxRepairs = 3;
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger replyCount = new AtomicInteger();

    StructuredOutputRepair.Result<Parsed> result =
        StructuredOutputRepair.parseOrRepair(
            hint -> {
              int n = replyCount.getAndIncrement();
              return n < 2 ? "garbage" : "{ok:valid}";
            },
            validator(calls),
            () -> new Parsed("fallback"),
            maxRepairs);

    assertFalse(result.usedFallback(), "should succeed without falling back");
    assertNotNull(result.value());
    assertEquals("{ok:valid}", result.value().value());
    assertTrue(result.attempts() <= maxRepairs + 1, "attempts bounded to maxRepairs+1");
    assertEquals(3, result.attempts());
  }

  // EC-005: always malformed -> fallback used, attempts == maxRepairs+1, validator not over-called.
  @Test
  public void fallbackWhenAlwaysMalformed() {
    int maxRepairs = 2;
    AtomicInteger calls = new AtomicInteger();
    Parsed fallback = new Parsed("fallback");

    StructuredOutputRepair.Result<Parsed> result =
        StructuredOutputRepair.parseOrRepair(
            hint -> "garbage", validator(calls), () -> fallback, maxRepairs);

    assertTrue(result.usedFallback(), "should fall back");
    assertEquals(fallback, result.value());
    assertNotNull(result.value());
    assertEquals(maxRepairs + 1, result.attempts(), "attempts bounded to maxRepairs+1");
    assertTrue(
        calls.get() <= maxRepairs + 1,
        "validator must not be called more than maxRepairs+1 times, was " + calls.get());
  }
}
