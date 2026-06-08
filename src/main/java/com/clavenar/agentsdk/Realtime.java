package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;

/** Helpers for gating OpenAI Realtime function calls from a websocket message pump. */
public final class Realtime {
  private Realtime() {}

  /**
   * The terminal event for one Realtime tool call: the {@code call_id} + final JSON-encoded {@code
   * arguments} from {@code response.function_call_arguments.done}, plus the tool {@code name} from
   * the matching {@code response.output_item.added}.
   */
  public record FunctionCallDone(String callId, String name, String arguments) {}

  /**
   * Normalize a done event into a {@link NormalizedToolCall}. Arguments that don't parse are
   * forwarded as a raw JSON string so a malformed-args policy rule can still inspect the attempt.
   */
  public static NormalizedToolCall normalize(FunctionCallDone evt) {
    JsonNode input;
    try {
      input = Json.MAPPER.readTree(evt.arguments());
      if (input == null) {
        input = Json.MAPPER.getNodeFactory().textNode(evt.arguments());
      }
    } catch (Exception e) {
      input = Json.MAPPER.getNodeFactory().textNode(evt.arguments());
    }
    return new NormalizedToolCall(evt.callId(), evt.name(), input);
  }

  /** One-shot inspect for a Realtime function call. Returns the verdict; never throws on a deny. */
  public static Verdict inspect(FunctionCallDone evt, ClavenarOptions opts) {
    return new ClavenarInspector(opts).inspect(normalize(evt));
  }
}
