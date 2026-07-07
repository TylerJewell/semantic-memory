package com.example.application.model;

import java.util.UUID;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.functions.Generator;
import com.github.tjake.jlama.safetensors.prompt.PromptContext;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

/**
 * langchain4j {@link ChatModel} backed by an in-JVM Jlama {@link AbstractModel}.
 * The model may be {@code null} (it is loaded lazily by the provider); it is not
 * dereferenced until {@link #doChat} is invoked.
 */
public final class JlamaChatModel implements ChatModel {

  private final AbstractModel model;

  public JlamaChatModel(AbstractModel model) {
    this.model = model;
  }

  @Override
  public ChatResponse doChat(ChatRequest request) {
    String prompt = latestUserText(request);
    PromptContext ctx = PromptContext.of(prompt);
    // Extraction needs only a short JSON reply; a small token budget keeps CPU inference fast.
    Generator.Response response =
        model.generate(UUID.randomUUID(), ctx, 0.3f, 128, (t, f) -> {});
    // The Akka SDK's interaction logging maps finishReason + tokenUsage on every response; leaving
    // them null makes it throw a scala.MatchError. Always populate both.
    return ChatResponse.builder()
        .aiMessage(AiMessage.from(response.responseText))
        .finishReason(FinishReason.STOP)
        .tokenUsage(new TokenUsage(response.promptTokens, response.generatedTokens))
        .build();
  }

  private static String latestUserText(ChatRequest request) {
    java.util.List<ChatMessage> messages = request.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof UserMessage userMessage) {
        return userMessage.singleText();
      }
    }
    return "";
  }
}
