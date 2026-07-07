package com.example.application.conflict;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EntityResolverTest {

  private final Map<String, String> aliases = Map.of("acme corp", "Acme");

  @Test
  void ec030_aliasAware_resolvesAliasAndStripsSuffix() {
    EntityResolver resolver = new EntityResolver(EntityResolver.Mode.ALIAS_AWARE, aliases);

    assertTrue(resolver.sameEntity("Acme Corp", "Acme"));
    assertTrue(resolver.canonical("Acme, Inc.").equals(resolver.canonical("Acme")));
  }

  @Test
  void ec030_strict_doesNotResolveAlias() {
    EntityResolver resolver = new EntityResolver(EntityResolver.Mode.STRICT, aliases);

    assertFalse(resolver.sameEntity("Acme Corp", "Acme"));
  }
}
