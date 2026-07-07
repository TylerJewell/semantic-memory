package com.example.application.conflict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContestedReadTest {

  private static Triple authored(String object) {
    return new Triple(
        "acme", "employees", object, new ProvenanceEnvelope(Layer.AUTHORED, "src", Instant.EPOCH, 1.0), true);
  }

  private final Triple acme = authored("Acme");
  private final Triple globex = authored("Globex");
  private final Resolution.Contested contested =
      new Resolution.Contested(List.of(acme, globex), "authored-tie");

  @Test
  void ec024_freezeIncumbent_returnsIncumbentContested() {
    ReadResult result =
        ContestedRead.read(contested, Optional.of(acme), ContestedRead.Mode.FREEZE_INCUMBENT);

    assertNotNull(result);
    ReadResult.Single single = assertInstanceOf(ReadResult.Single.class, result);
    assertEquals("Acme", single.value().object());
    assertTrue(single.contested());
    // never blank, never the losing candidate
    assertFalse(single.value().object().isBlank());
    assertFalse("Globex".equals(single.value().object()));
  }

  @Test
  void ec028_noIncumbent_returnsBoth() {
    ReadResult freeze =
        ContestedRead.read(contested, Optional.empty(), ContestedRead.Mode.FREEZE_INCUMBENT);
    assertNotNull(freeze);
    ReadResult.Both both = assertInstanceOf(ReadResult.Both.class, freeze);
    assertEquals(2, both.values().size());
    assertTrue(both.values().contains(acme));
    assertTrue(both.values().contains(globex));

    ReadResult bothMode =
        ContestedRead.read(contested, Optional.of(acme), ContestedRead.Mode.BOTH_CONTESTED);
    assertNotNull(bothMode);
    ReadResult.Both both2 = assertInstanceOf(ReadResult.Both.class, bothMode);
    assertEquals(2, both2.values().size());

    // never null, never an empty/blank value set
    for (Triple t : both.values()) {
      assertFalse(t.object().isBlank());
    }
  }

  @Test
  void unknownMode_returnsUnknown() {
    ReadResult result =
        ContestedRead.read(contested, Optional.of(acme), ContestedRead.Mode.UNKNOWN);
    assertNotNull(result);
    assertInstanceOf(ReadResult.Unknown.class, result);
  }
}
