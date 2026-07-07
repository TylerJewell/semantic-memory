package com.example.application.conflict;

import com.example.domain.Triple;
import java.util.List;

public sealed interface ReadResult permits ReadResult.Single, ReadResult.Both, ReadResult.Unknown {
  record Single(Triple value, boolean contested) implements ReadResult {}

  record Both(List<Triple> values) implements ReadResult {} // both tagged contested

  record Unknown() implements ReadResult {}
}
