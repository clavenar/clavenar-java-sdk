package com.clavenar.agentsdk;

import java.util.List;

/**
 * Thrown (enforce mode) when clavenar returns a 429 for a tool call — the request was rejected
 * <em>before</em> evaluation, by the request-velocity gate ({@code rate_limited}) or the per-tenant
 * spend gate ({@code quota_exceeded}). Not retried by the transport: honor {@link
 * #retryAfterSecs()} (set on {@code rate_limited} only) or fail the operation.
 */
public final class ClavenarRateLimited extends ClavenarException {
  private final String toolName;
  private final String code;
  private final List<String> reasons;
  private final Integer retryAfterSecs;
  private final String layer;
  private final String correlationId;

  ClavenarRateLimited(
      String toolName,
      String code,
      List<String> reasons,
      Integer retryAfterSecs,
      String layer,
      String correlationId) {
    super(
        "clavenar "
            + code
            + " for tool \""
            + toolName
            + "\""
            + (retryAfterSecs != null ? " (retry after " + retryAfterSecs + "s)" : ""));
    this.toolName = toolName;
    this.code = code;
    this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    this.retryAfterSecs = retryAfterSecs;
    this.layer = layer;
    this.correlationId = correlationId;
  }

  public String toolName() {
    return toolName;
  }

  /** The 429 code: {@code rate_limited} (velocity gate) or {@code quota_exceeded} (spend gate). */
  public String code() {
    return code;
  }

  public List<String> reasons() {
    return reasons;
  }

  /** Seconds to wait before retrying; null on {@code quota_exceeded}. */
  public Integer retryAfterSecs() {
    return retryAfterSecs;
  }

  /** The stage that produced the rate limit when reported, else null. */
  public String layer() {
    return layer;
  }

  /** clavenar's correlation id for the audit ledger when reported, else null. */
  public String correlationId() {
    return correlationId;
  }
}
