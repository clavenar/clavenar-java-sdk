package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ClientMigrationFixtureTest {
  @Test
  void packagesExplicitDecisionMigrationBoundary() throws Exception {
    JsonNode fixture =
        Json.MAPPER.readTree(
            Files.readString(Path.of("fixtures/client-migration-v1.fixture.json")));
    JsonNode schema =
        Json.MAPPER.readTree(Files.readString(Path.of("fixtures/client-migration-v1.schema.json")));
    assertEquals("clavenar.client-migration/v1", fixture.path("contract").asText());
    assertEquals("1.4.0", fixture.path("minimumSafeVersions").path("java").asText());
    assertEquals(426, fixture.path("legacyRejection").path("httpStatus").asInt());
    assertFalse(fixture.path("legacyRejection").path("executable").asBoolean());
    assertEquals(0, fixture.path("legacyRejection").path("toolEffectCount").asInt());
    assertTrue(fixture.path("invariants").path("legacyInspectionCannotExecute").asBoolean());
    assertEquals(
        fixture.path("contract").asText(),
        schema.path("properties").path("contract").path("const").asText());
  }
}
