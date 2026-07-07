package com.example.application.model;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Structured-output repair loop for small local LLMs whose JSON often violates a schema. Retries
 * with the violation echoed back as a hint, bounded, then falls back to a reduced-schema parse.
 *
 * <p>Pure logic — no real LLM. The caller supplies the model replies via {@code reply}.
 */
public final class StructuredOutputRepair {

  private StructuredOutputRepair() {}

  /** Parses a raw model reply, throwing on a schema violation. */
  public interface Validator<T> {
    T parse(String raw) throws Exception;
  }

  /** Outcome of a repair loop: the value, how many attempts were made, and whether fallback ran. */
  public record Result<T>(T value, int attempts, boolean usedFallback) {}

  /**
   * Try {@code validator.parse(reply.apply(hint))}; on failure, re-invoke {@code reply} with the
   * error text as the next hint, up to {@code maxRepairs} times. If still failing, use {@code
   * fallback} (a reduced-schema parse that MUST succeed). Never returns a null value.
   *
   * <p>Attempt 1 uses {@code hint=""}; total attempts are bounded to {@code maxRepairs + 1}.
   */
  public static <T> Result<T> parseOrRepair(
      Function<String, String> reply,
      Validator<T> validator,
      Supplier<T> fallback,
      int maxRepairs) {
    int totalAttempts = maxRepairs + 1;
    String hint = "";
    int attempts = 0;
    for (int i = 0; i < totalAttempts; i++) {
      attempts++;
      try {
        T value = validator.parse(reply.apply(hint));
        return new Result<>(value, attempts, false);
      } catch (Exception e) {
        hint = String.valueOf(e.getMessage());
      }
    }
    return new Result<>(fallback.get(), attempts, true);
  }
}
