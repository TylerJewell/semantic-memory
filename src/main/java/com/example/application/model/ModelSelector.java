package com.example.application.model;

import com.example.application.embeddings.*;

public final class ModelSelector {
  private ModelSelector() {}

  public static Embeddings selectEmbeddings(String geminiKey) {
    return (geminiKey == null || geminiKey.isBlank())
        ? new LocalOnnxEmbeddings()
        : new GeminiEmbeddings();
  }

  public static Embeddings embeddings() {
    return selectEmbeddings(System.getenv("GOOGLE_AI_GEMINI_API_KEY"));
  }

  public static akka.javasdk.agent.ModelProvider selectChatProvider(String geminiKey) {
    return (geminiKey == null || geminiKey.isBlank())
        ? new JlamaModelProvider()
        : akka.javasdk.agent.ModelProvider.googleAiGemini();
  }

  public static akka.javasdk.agent.ModelProvider chatProvider() {
    return selectChatProvider(System.getenv("GOOGLE_AI_GEMINI_API_KEY"));
  }

  /** True when no Gemini key is set, so the in-JVM Jlama chat model is used. */
  public static boolean usingLocalChat() {
    String key = System.getenv("GOOGLE_AI_GEMINI_API_KEY");
    return key == null || key.isBlank();
  }
}
