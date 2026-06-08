package com.clavenar.examples.realtime;

import com.clavenar.agentsdk.ClavenarOptions;
import com.clavenar.agentsdk.Realtime;
import com.clavenar.agentsdk.Verdict;

/** Gate an OpenAI Realtime function call before dispatching it from your websocket message pump. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    var opts = ClavenarOptions.builder(env("CLAVENAR_ENDPOINT", "http://localhost:8088")).build();

    // In a real pump you'd assemble this from response.output_item.added (call_id + name) and
    // response.function_call_arguments.done (arguments).
    var evt = new Realtime.FunctionCallDone("call_42", "wire_transfer", "{\"amount\":5000,\"to\":\"external\"}");

    Verdict v = Realtime.inspect(evt, opts);
    switch (v.kind()) {
      case DENY -> System.out.println("deny " + evt.name() + ": " + v.reasons());
      case PENDING -> System.out.println("pending " + evt.name() + " (" + v.correlationId() + ")");
      default -> System.out.println("allow — dispatch " + evt.name());
    }
  }

  private static String env(String key, String fallback) {
    var v = System.getenv(key);
    return v == null || v.isEmpty() ? fallback : v;
  }
}
