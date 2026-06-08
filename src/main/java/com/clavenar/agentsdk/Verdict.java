package com.clavenar.agentsdk;

import java.util.List;

/**
 * Result of inspecting one tool call. {@link ClavenarInspector#inspect} returns it directly; {@code
 * inspectAll} and the wrap facade turn DENY / PENDING into thrown errors in enforce mode.
 */
public final class Verdict {
  private final VerdictKind kind;
  private final String correlationId;
  private final List<String> reasons;
  private final List<String> reviewReasons;
  private final String intentCategory;
  private final String layer;

  Verdict(
      VerdictKind kind,
      String correlationId,
      List<String> reasons,
      List<String> reviewReasons,
      String intentCategory,
      String layer) {
    this.kind = kind;
    this.correlationId = correlationId;
    this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    this.reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
    this.intentCategory = intentCategory == null ? "" : intentCategory;
    this.layer = layer;
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
}
