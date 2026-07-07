package com.example.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.domain.Layer;
import com.example.domain.ProvenanceEnvelope;
import com.example.domain.Triple;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live integration test for the FlureeClient provenance layer. Requires the local
 * Fluree server running at http://127.0.0.1:8090 with ledger memory:main. Tagged
 * "live" and named *IT so it runs under the failsafe plugin (mvn verify), not the
 * normal surefire suite.
 */
@Tag("live")
class FlureeProvenanceIT {

  @Test
  void insertsAndQueriesPerTripleProvenance() {
    Triple t =
        new Triple(
            "tyler",
            "currentEmployer",
            "acme",
            new ProvenanceEnvelope(Layer.AUTHORED, "vault/people/tyler.md", Instant.now(), 1.0),
            true);

    String commit = FlureeClient.insertAssertion(t);
    assertFalse(commit == null || commit.isBlank(), "insertAssertion must return a non-empty commit");

    List<FlureeClient.Envelope> envelopes =
        FlureeClient.queryEnvelopes("tyler", "currentEmployer");
    assertFalse(envelopes.isEmpty(), "queryEnvelopes must return at least one envelope");

    boolean present =
        envelopes.stream()
            .anyMatch(
                e ->
                    e.object().equals("acme")
                        && e.layer().equals("authored")
                        && e.source().equals("vault/people/tyler.md")
                        && e.conf() == 1.0);
    assertTrue(
        present,
        "expected envelope {object=acme, layer=authored, source=vault/people/tyler.md, conf=1.0} "
            + "among results but got: " + envelopes);
  }
}
