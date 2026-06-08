package com.clavenar.examples.nativeanthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.clavenar.agentsdk.Clavenar;
import com.clavenar.agentsdk.ClavenarDenied;
import com.clavenar.agentsdk.ClavenarOptions;

/** Wrap an anthropic-java client so every tool_use is inspected before {@code create} returns. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    AnthropicClient base =
        AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).build();
    AnthropicClient client =
        Clavenar.wrap(
            base, ClavenarOptions.builder(env("CLAVENAR_ENDPOINT", "http://localhost:8088")).build());

    var params =
        MessageCreateParams.builder()
            .model(Model.CLAUDE_OPUS_4_8)
            .maxTokens(1024)
            .addUserMessage("delete the alice user")
            .build();

    try {
      Message msg = client.messages().create(params);
      System.out.println("response cleared: " + msg.content().size() + " content block(s)");
    } catch (ClavenarDenied d) {
      System.out.println("blocked " + d.toolName() + ": " + d.reasons());
    }
  }

  private static String env(String key, String fallback) {
    var v = System.getenv(key);
    return v == null || v.isEmpty() ? fallback : v;
  }
}
