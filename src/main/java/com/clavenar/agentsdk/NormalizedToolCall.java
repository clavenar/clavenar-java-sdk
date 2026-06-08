package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * A provider-agnostic tool call ready for inspection. {@code input} is the model's argument
 * payload, forwarded to clavenar verbatim.
 */
public record NormalizedToolCall(String id, String name, JsonNode input) {
  public NormalizedToolCall(String id, String name, JsonNode input) {
    this.id = id;
    this.name = name;
    this.input = input == null ? NullNode.getInstance() : input;
  }

  /**
   * Build a tool call from a JSON-encoded arguments string. Throws {@link ClavenarConfigException}
   * when the arguments aren't valid JSON (a model-contract violation). An empty string becomes an
   * empty object.
   */
  public static NormalizedToolCall fromJsonArguments(String id, String name, String argumentsJson) {
    try {
      JsonNode node =
          argumentsJson == null || argumentsJson.isEmpty()
              ? Json.MAPPER.createObjectNode()
              : Json.MAPPER.readTree(argumentsJson);
      return new NormalizedToolCall(id, name, node);
    } catch (Exception e) {
      throw new ClavenarConfigException(
          "tool call " + id + " (" + name + ") had unparseable arguments: " + e.getMessage());
    }
  }
}
