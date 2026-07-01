package com.example.domain;

import akka.javasdk.annotations.Description;
import java.util.List;

/**
 * The structured knowledge graph the LLM extracts from a piece of text.
 *
 * <p>This record is the "structured output" contract. The Akka agent uses
 * {@code responseConformsTo(KnowledgeGraph.class)} to force Gemini to return JSON
 * matching this shape — which is exactly the job Cognee delegates to the Python
 * {@code instructor}/{@code LiteLLM} layer. Here it is native to the SDK.
 */
public record KnowledgeGraph(
    @Description("All entities (people, things, concepts, technologies) mentioned in the text")
        List<Entity> entities,
    @Description("Directed relationships connecting two entities by a verb/predicate")
        List<Relationship> relationships) {

  public record Entity(
      @Description("The canonical name of the entity, e.g. 'Akka'") String name,
      @Description("The entity type/category, e.g. 'Technology', 'Person', 'Concept'")
          String type) {}

  public record Relationship(
      @Description("Name of the source entity (must match one of the entity names)")
          String source,
      @Description("The verb/predicate linking source to target, e.g. 'provides', 'is part of'")
          String label,
      @Description("Name of the target entity (must match one of the entity names)")
          String target) {}
}
