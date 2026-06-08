package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;

/** Identifies the tool call an {@code onVerdict} / {@code onPolicyError} callback fired for. */
public record VerdictContext(String toolName, String toolUseId, JsonNode toolInput) {}
