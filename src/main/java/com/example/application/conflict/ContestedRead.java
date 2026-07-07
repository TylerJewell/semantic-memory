package com.example.application.conflict;

import com.example.domain.Triple;
import java.util.List;
import java.util.Optional;

public final class ContestedRead {
  public enum Mode {
    FREEZE_INCUMBENT,
    BOTH_CONTESTED,
    UNKNOWN
  }

  private ContestedRead() {}

  /**
   * Read semantics for a CONTESTED fact (cascade produced no winner). Never returns null, never
   * returns a blank value, and never silently flips to a non-incumbent value.
   *
   * <ul>
   *   <li>FREEZE_INCUMBENT + incumbent present -> Single(incumbent, contested=true)
   *   <li>FREEZE_INCUMBENT + no incumbent -> Both(contested.candidates())
   *   <li>BOTH_CONTESTED -> Both(contested.candidates())
   *   <li>UNKNOWN -> Unknown()
   * </ul>
   */
  public static ReadResult read(
      Resolution.Contested contested, Optional<Triple> incumbent, Mode mode) {
    return switch (mode) {
      case FREEZE_INCUMBENT ->
          incumbent
              .<ReadResult>map(t -> new ReadResult.Single(t, true))
              .orElseGet(() -> new ReadResult.Both(List.copyOf(contested.candidates())));
      case BOTH_CONTESTED -> new ReadResult.Both(List.copyOf(contested.candidates()));
      case UNKNOWN -> new ReadResult.Unknown();
    };
  }
}
