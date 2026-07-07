package com.example.application.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.application.embeddings.GeminiEmbeddings;
import com.example.application.embeddings.LocalOnnxEmbeddings;

import akka.javasdk.agent.ModelProvider;

/** Maps EC-002: chat provider + embeddings selection seam. */
class ModelSelectorTest {

  @Test
  void selectsCustomJlamaChatProviderWhenNoKey() {
    assertInstanceOf(ModelProvider.Custom.class, ModelSelector.selectChatProvider(null));
    assertInstanceOf(ModelProvider.Custom.class, ModelSelector.selectChatProvider("  "));
  }

  @Test
  void selectsGeminiChatProviderWhenKeyPresent() {
    ModelProvider provider = ModelSelector.selectChatProvider("some-key");
    assertFalse(provider instanceof ModelProvider.Custom);
  }

  @Test
  void selectsEmbeddingsBySeam() {
    assertTrue(ModelSelector.selectEmbeddings(null) instanceof LocalOnnxEmbeddings);
    assertTrue(ModelSelector.selectEmbeddings("k") instanceof GeminiEmbeddings);
  }
}
