package com.example.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.application.embeddings.Embeddings;
import com.example.application.embeddings.GeminiEmbeddings;
import com.example.application.embeddings.LocalOnnxEmbeddings;
import com.example.application.model.ModelSelector;
import org.junit.jupiter.api.Test;

public class EmbeddingsSeamTest {

  @Test
  public void selectsLocalWhenNoKey() {
    assertInstanceOf(LocalOnnxEmbeddings.class, ModelSelector.selectEmbeddings(null));
  }

  @Test
  public void selectsGeminiWhenKeyPresent() {
    assertInstanceOf(GeminiEmbeddings.class, ModelSelector.selectEmbeddings("some-key"));
  }

  @Test
  public void bothReport768Dimensions() {
    Embeddings local = new LocalOnnxEmbeddings();
    Embeddings gemini = new GeminiEmbeddings();
    assertEquals(768, local.dimensions());
    assertEquals(768, gemini.dimensions());
  }

  @Test
  public void modelIdsAreNonBlankAndDistinct() {
    String localId = new LocalOnnxEmbeddings().modelId();
    String geminiId = new GeminiEmbeddings().modelId();
    assertFalse(localId.isBlank());
    assertFalse(geminiId.isBlank());
    assertNotEquals(localId, geminiId);
  }

  @Test
  public void localEmbedProduces768Length() {
    assertEquals(768, new LocalOnnxEmbeddings().embed("hello").length);
  }
}
