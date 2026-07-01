package com.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;

/**
 * The answer specialist. Given a question plus context retrieved from Fluree,
 * it produces the shortest grounded answer the context supports — an entity
 * name, a date, or "yes" / "no" for boolean questions. This terseness matches
 * how academic QA benchmarks (HotpotQA, NaturalQuestions) score answers.
 */
@Component(id = "qa-agent")
public class QaAgent extends Agent {

  public record Question(String question, String context) {}

  private static final String SYSTEM_MESSAGE =
      """
      Answer the question using ONLY the supplied context.

      Rules — follow strictly:
      - Give the SHORTEST answer the question allows. Prefer a single entity name,
        a single number, a date, or "yes" / "no". Never write a sentence when a
        phrase will do.
      - For yes/no questions answer exactly "yes" or "no".
      - Do NOT restate the question. Do NOT explain. Do NOT add courtesy phrases.
      - If the context does not support an answer, reply with exactly:
        I don't know.

      Examples:
        Q: Who wrote Hamlet?  ->  Shakespeare
        Q: Are X and Y the same nationality?  ->  yes
        Q: In what year was Z founded?  ->  1889
      """
          .stripIndent();

  public Effect<String> answer(Question q) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage("Context:\n" + q.context() + "\n\nQuestion: " + q.question())
        .thenReply();
  }
}
