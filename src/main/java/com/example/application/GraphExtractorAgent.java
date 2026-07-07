package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import com.example.application.model.LocalGraphParser;
import com.example.application.model.ModelSelector;
import com.example.domain.KnowledgeGraph;

/**
 * The "smart reader" — the LLM step that turns raw text into structured graph.
 *
 * <p>Reads a chunk of text and returns a typed {@link KnowledgeGraph}. The
 * {@code responseConformsTo} call makes the Akka SDK generate a JSON schema from
 * the record and validate the model's reply against it — no external
 * structured-output library needed; LangChain4j's schema enforcement does the
 * work under the hood.
 */
@Component(id = "graph-extractor")
public class GraphExtractorAgent extends Agent {

  private static final String SYSTEM_MESSAGE =
      """
      You extract a knowledge graph from the given text.
      Identify the key entities and the directed relationships between them.
      Rules:
      - Only include entities and relationships explicitly supported by the text.
      - Use concise, canonical entity names (no trailing punctuation).
      - Every relationship's source and target MUST exactly match an entity name you list.
      - Prefer specific relationship verbs (e.g. "provides", "is part of", "runs on").
      """
          .stripIndent();

  public Effect<KnowledgeGraph> extract(String text) {
    // Key-gated provider: no GOOGLE_AI_GEMINI_API_KEY -> in-JVM Jlama (zero external network);
    // key present -> Gemini. Overrides the application.conf default per call.
    if (ModelSelector.usingLocalChat()) {
      // A 1.1B local model can't honor the SDK's strict JSON-schema (responseConformsTo) path
      // reliably, so we few-shot prompt it for compact JSON and parse the reply ourselves with a
      // bounded repair + lenient fallback. No external network is touched.
      // A non-blank system message is required by the SDK; the in-JVM JlamaChatModel ignores it
      // (it prompts from the user message only), so the few-shot instructions live in userMessage.
      return effects()
          .model(ModelSelector.chatProvider())
          .systemMessage(SYSTEM_MESSAGE)
          .userMessage(localPrompt(text))
          .map(LocalGraphParser::parse)
          .thenReply();
    }
    return effects()
        .model(ModelSelector.chatProvider())
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(text)
        .responseConformsTo(KnowledgeGraph.class)
        .thenReply();
  }

  // Few-shot prompt that reliably steers a tiny model to emit a leading JSON object of the exact
  // {"relationships":[{"source","label","target"}]} shape LocalGraphParser expects.
  private static String localPrompt(String text) {
    return """
        You extract subject-predicate-object relationships from a sentence as JSON.
        Reply with ONLY a JSON object, no prose.

        Sentence: Alice lives in Boston.
        JSON: {"relationships":[{"source":"Alice","label":"lives in","target":"Boston"}]}

        Sentence: %s
        JSON:"""
        .formatted(text)
        .stripIndent();
  }
}
