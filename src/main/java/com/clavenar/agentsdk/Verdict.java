package com.clavenar.agentsdk;

import java.util.List;

/**
 * Result of inspecting one tool call. {@link ClavenarInspector#inspect} returns it directly; {@code
 * inspectAll} and the wrap facade turn DENY / PENDING / RATE_LIMITED into thrown errors in enforce
 * mode.
 */
public final class Verdict {
  private final VerdictKind kind;
  private final String correlationId;
  private final List<String> reasons;
  private final List<String> reviewReasons;
  private final String intentCategory;
  private final String layer;
  private final VerdictDetail detail;
  private final String rateLimitCode;
  private final Integer retryAfterSecs;

  Verdict(
      VerdictKind kind,
      String correlationId,
      List<String> reasons,
      List<String> reviewReasons,
      String intentCategory,
      String layer) {
    this(kind, correlationId, reasons, reviewReasons, intentCategory, layer, null);
  }

  Verdict(
      VerdictKind kind,
      String correlationId,
      List<String> reasons,
      List<String> reviewReasons,
      String intentCategory,
      String layer,
      VerdictDetail detail) {
    this(kind, correlationId, reasons, reviewReasons, intentCategory, layer, detail, null, null);
  }

  private Verdict(
      VerdictKind kind,
      String correlationId,
      List<String> reasons,
      List<String> reviewReasons,
      String intentCategory,
      String layer,
      VerdictDetail detail,
      String rateLimitCode,
      Integer retryAfterSecs) {
    this.kind = kind;
    this.correlationId = correlationId;
    this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    this.reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
    this.intentCategory = intentCategory == null ? "" : intentCategory;
    this.layer = layer;
    this.detail = detail;
    this.rateLimitCode = rateLimitCode;
    this.retryAfterSecs = retryAfterSecs;
  }

  static Verdict rateLimited(
      String correlationId,
      String rateLimitCode,
      List<String> reasons,
      Integer retryAfterSecs,
      String layer) {
    return new Verdict(
        VerdictKind.RATE_LIMITED,
        correlationId,
        reasons,
        null,
        null,
        layer,
        null,
        rateLimitCode,
        retryAfterSecs);
  }

  public VerdictKind kind() {
    return kind;
  }

  /** clavenar's correlation id when the deployment sets the header, else null. */
  public String correlationId() {
    return correlationId;
  }

  public List<String> reasons() {
    return reasons;
  }

  public List<String> reviewReasons() {
    return reviewReasons;
  }

  public String intentCategory() {
    return intentCategory;
  }

  /** The stage that produced a deny when reported (brain, policy, hil, ...), else null. */
  public String layer() {
    return layer;
  }

  /** The verbose-verdict per-detector breakdown when the gateway opts in, else null. */
  public VerdictDetail detail() {
    return detail;
  }

  /**
   * The 429 code ({@code rate_limited} or {@code quota_exceeded}) on a RATE_LIMITED verdict, else
   * null.
   */
  public String rateLimitCode() {
    return rateLimitCode;
  }

  /**
   * Seconds to wait before retrying, when the gateway reports it on a RATE_LIMITED verdict; null
   * otherwise (always null on {@code quota_exceeded}).
   */
  public Integer retryAfterSecs() {
    return retryAfterSecs;
  }
}
