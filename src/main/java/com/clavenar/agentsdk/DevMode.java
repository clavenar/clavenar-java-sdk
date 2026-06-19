package com.clavenar.agentsdk;

/**
 * Developer-mode rendering of a denied tool call's verbose-verdict breakdown. When {@link
 * ClavenarOptions#devMode()} is set, the SDK writes a readable panel (per-detector scores, degraded
 * lanes, reasons, correlation id) to stderr before throwing {@link ClavenarDenied}.
 */
final class DevMode {
  private DevMode() {}

  /**
   * Render a denied tool call as a readable console panel. Pure (no I/O) so it's unit-testable;
   * falls back to a hint when the gateway didn't include detail (verbose-verdicts off).
   */
  static String renderDenyPanel(ClavenarDenied d) {
    StringBuilder b = new StringBuilder();
    b.append("━━ clavenar denied: ").append(d.toolName()).append(" ━━");

    StringBuilder meta = new StringBuilder();
    if (d.layer() != null && !d.layer().isEmpty()) {
      meta.append("layer=").append(d.layer());
    }
    if (!d.intentCategory().isEmpty()) {
      if (meta.length() > 0) meta.append("  ");
      meta.append("intent=").append(d.intentCategory());
    }
    if (d.correlationId() != null && !d.correlationId().isEmpty()) {
      if (meta.length() > 0) meta.append("  ");
      meta.append("correlation=").append(d.correlationId());
    }
    if (meta.length() > 0) {
      b.append("\n  ").append(meta);
    }

    if (!d.reasons().isEmpty()) {
      b.append("\n  reasons:");
      for (String r : d.reasons()) {
        b.append("\n    - ").append(r);
      }
    }

    VerdictDetail detail = d.detail();
    if (detail != null && !detail.detectors().isEmpty()) {
      b.append("\n  detectors:");
      for (VerdictDetail.DetectorScore det : detail.detectors()) {
        b.append(String.format("%n    %-22s%.2f", det.detector(), det.score()));
        if (det.flagged()) {
          b.append("  ⚠ flagged");
        }
      }
      if (!detail.degraded().isEmpty()) {
        b.append("\n  degraded: ").append(String.join(", ", detail.degraded()));
      }
    } else {
      b.append(
          "\n  (no per-detector detail — run the gateway with CLAVENAR_PROXY_VERBOSE_VERDICTS=true)");
    }

    if (d.correlationId() != null && !d.correlationId().isEmpty()) {
      b.append("\n  trace: look up correlation ")
          .append(d.correlationId())
          .append(" in the console audit trail");
    }
    return b.toString();
  }

  /** Write the dev-mode deny panel to stderr. Best-effort. */
  static void emitDenyPanel(ClavenarDenied d) {
    System.err.println(renderDenyPanel(d));
  }
}
