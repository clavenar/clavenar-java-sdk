package com.clavenar.examples.nativeopenai;

import com.clavenar.agentsdk.Clavenar;
import com.clavenar.agentsdk.ClavenarDenied;
import com.clavenar.agentsdk.ClavenarOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/**
 * Wrap an openai-java client so every tool call is inspected before {@code create} returns. The wrap
 * is one line at boot; the call site is unchanged.
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    OpenAIClient base = OpenAIOkHttpClient.builder().apiKey(System.getenv("OPENAI_API_KEY")).build();
    OpenAIClient client =
        Clavenar.wrap(
            base, ClavenarOptions.builder(env("CLAVENAR_ENDPOINT", "http://localhost:8088")).build());

    var params =
        ChatCompletionCreateParams.builder()
            .model("gpt-4o")
            .addUserMessage("delete the alice user")
            .build();

    try {
      ChatCompletion completion = client.chat().completions().create(params);
      System.out.println("response cleared: " + completion.choices().size() + " choice(s)");
    } catch (ClavenarDenied d) {
      System.out.println("blocked " + d.toolName() + ": " + d.reasons());
    }
  }

  private static String env(String key, String fallback) {
    var v = System.getenv(key);
    return v == null || v.isEmpty() ? fallback : v;
  }
}
