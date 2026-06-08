package com.clavenar.agentsdk;

import java.util.List;

/** Thrown (enforce mode) when clavenar rejects a tool call. */
public final class ClavenarDenied extends ClavenarException {
  private final String toolName;
  private final List<String> reasons;
  private final List<String> reviewReasons;
  private final String intentCategory;
  private final String layer;
  private final String correlationId;

  ClavenarDenied(
      String toolName,
      List<String> reasons,
      List<String> reviewReasons,
      String intentCategory,
      String layer,
      String correlationId) {
    super("clavenar denied tool \"" + toolName + "\": " + String.join(" | ", reasons));
    this.toolName = toolName;
    this.reasons = List.copyOf(reasons);
    this.reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
    this.intentCategory = intentCategory == null ? "" : intentCategory;
    this.layer = layer;
    this.correlationId = correlationId;
  }

  public String toolName() {
    return toolName;
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

  /** The stage that produced the deny when reported, else null. */
  public String layer() {
    return layer;
  }

  /** clavenar's correlation id for the audit ledger when reported, else null. */
  public String correlationId() {
    return correlationId;
  }
}
