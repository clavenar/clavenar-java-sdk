package com.clavenar.agentsdk;

import java.time.Duration;

/** Shared test helpers. */
final class Fixtures {
  private Fixtures() {}

  static ClavenarOptions opts(String baseUrl) {
    return ClavenarOptions.builder(baseUrl)
        .retry(new RetryOptions(1, Duration.ofMillis(1)))
        .timeout(Duration.ofSeconds(2))
        .build();
  }

  static NormalizedToolCall sampleCall() {
    return NormalizedToolCall.fromJsonArguments("toolu_1", "delete_user", "{\"user\":\"alice\"}");
  }

  static String toolName(String body) {
    try {
      return Json.MAPPER.readTree(body).path("params").path("name").asText("");
    } catch (Exception e) {
      return "";
    }
  }
}
