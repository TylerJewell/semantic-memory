package com.example.application.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.domain.Layer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** EC-011: source -> layer mapping, overlap rejection, vault-leak guard. */
class SourceLayerResolverTest {

  @Test
  void mapsPathsToLayers() {
    SourceLayerResolver r = new SourceLayerResolver("vault/**", "corpus/**");
    assertEquals(Layer.AUTHORED, r.layerFor("vault/people/tyler.md"));
    assertEquals(Layer.DERIVED, r.layerFor("corpus/notes/x.md"));
  }

  @Test
  void identicalGlobsOverlap() {
    assertThrows(
        IllegalArgumentException.class, () -> new SourceLayerResolver("vault/**", "vault/**"));
  }

  @Test
  void prefixGlobsOverlap() {
    assertThrows(
        IllegalArgumentException.class, () -> new SourceLayerResolver("data/**", "data/sub/**"));
  }

  @Test
  void detectsVaultLeak() {
    String vault = "name: Tyler\nrole: Founder";
    assertTrue(isVaultLeak(vault, List.of(vault)));
    assertFalse(isVaultLeak("completely unrelated corpus text about weather", List.of(vault)));
  }

  private static boolean isVaultLeak(String corpus, List<String> vaults) {
    return SourceLayerResolver.isVaultLeak(corpus, vaults);
  }
}
