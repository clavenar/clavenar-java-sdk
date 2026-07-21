package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

final class RetrySeparationFixtureTest {
  @Test
  void packagesDecisionOnlyRetryContract() throws Exception {
    JsonNode fixture =
        Json.MAPPER.readTree(
            Files.readString(Path.of("fixtures/retry-separation-v1.fixture.json")));
    assertEquals("clavenar.retry-separation/v1", fixture.path("contract").asText());
    JsonNode decision = caseById(fixture, "explicit-side-effect-free-decision");
    JsonNode execution = caseById(fixture, "sdk-registered-executor");
    assertTrue(decision.path("automaticTransportRetry").asBoolean());
    assertEquals(0, decision.path("maximumEffectAttempts").asInt());
    assertFalse(execution.path("automaticTransportRetry").asBoolean());
    assertEquals(1, execution.path("maximumEffectAttempts").asInt());
    assertTrue(
        fixture
            .path("invariants")
            .path("executorFailuresNeverEnterTransportRetryLoop")
            .asBoolean());
  }

  private static JsonNode caseById(JsonNode fixture, String id) {
    return StreamSupport.stream(fixture.path("cases").spliterator(), false)
        .filter(value -> id.equals(value.path("id").asText()))
        .findFirst()
        .orElseThrow();
  }
}
