package com.example.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import com.github.tjake.jlama.tensor.operations.TensorOperationsProvider;

import akka.javasdk.agent.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;

/** Maps EC-007: Jlama runs its Panama Vector path under the test JVM. */
class JlamaCompatTest {

  @Test
  void usesPanamaTensorOperations() {
    assertEquals(
        "PanamaTensorOperations",
        TensorOperationsProvider.get().getClass().getSimpleName());
  }

  @Test
  void jlamaChatModelIsALangchainChatModel() {
    assertInstanceOf(ChatModel.class, new JlamaChatModel(null));
  }

  @Test
  void chatProviderIsCustom() {
    assertInstanceOf(ModelProvider.Custom.class, ModelSelector.selectChatProvider(null));
  }
}
