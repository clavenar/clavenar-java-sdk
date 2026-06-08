package com.clavenar.agentsdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Body of {@code GET /pending/{id}}. {@code decision} is null until an operator decides. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClavenarPendingView(
    @JsonProperty("correlation_id") String correlationId,
    @JsonProperty("agent_id") String agentId,
    @JsonProperty("tool_type") String toolType,
    @JsonProperty("method") String method,
    @JsonProperty("review_reasons") List<String> reviewReasons,
    @JsonProperty("requested_at") String requestedAt,
    @JsonProperty("decided_at") String decidedAt,
    @JsonProperty("decision") String decision,
    @JsonProperty("decider_note") String deciderNote) {}
