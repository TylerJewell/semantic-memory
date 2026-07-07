package com.example.application.conflict;

import com.example.domain.Triple;
import java.util.List;

public sealed interface Resolution permits Resolution.Resolved, Resolution.Contested {
  record Resolved(Triple winner, String resolvedBy, List<Triple> flagged) implements Resolution {}

  record Contested(List<Triple> candidates, String reason) implements Resolution {}
}
