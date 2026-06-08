package com.clavenar.examples.customdispatcher;

import com.clavenar.agentsdk.ClavenarDenied;
import com.clavenar.agentsdk.ClavenarInspector;
import com.clavenar.agentsdk.ClavenarOptions;
import com.clavenar.agentsdk.ClavenarPending;
import com.clavenar.agentsdk.NormalizedToolCall;
import java.util.List;

/**
 * The provider-agnostic pattern — no provider SDK. Build NormalizedToolCalls from your framework's
 * tool-dispatch boundary (LangChain4j ToolExecutor, Spring AI ToolCallback, a custom loop) and
 * inspect them before running the tools.
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    var inspector =
        new ClavenarInspector(
            ClavenarOptions.builder(env("CLAVENAR_ENDPOINT", "http://localhost:8088"))
                .token(System.getenv("CLAVENAR_LITE_TOKEN"))
                .build());

    var calls =
        List.of(NormalizedToolCall.fromJsonArguments("call_1", "delete_user", "{\"user\":\"alice\"}"));

    try {
      inspector.inspectAll(calls);
      System.out.println("cleared " + calls.size() + " tool call(s) — dispatch them");
    } catch (ClavenarDenied d) {
      System.out.println("blocked " + d.toolName() + ": " + d.reasons());
    } catch (ClavenarPending p) {
      System.out.println("parked " + p.toolName() + " for review; waiting for an operator...");
      p.resolve();
      System.out.println("approved — dispatch " + p.toolName());
    }
  }

  private static String env(String key, String fallback) {
    var v = System.getenv(key);
    return v == null || v.isEmpty() ? fallback : v;
  }
}
