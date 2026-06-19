package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DevModeTest {

  @Test
  void rendersDetectorBreakdownWhenDetailPresent() {
    VerdictDetail detail =
        new VerdictDetail(
            List.of(
                new VerdictDetail.DetectorScore("persona_drift", 0.12, false),
                new VerdictDetail.DetectorScore("injection", 0.91, true)),
            List.of("injection"));
    ClavenarDenied d =
        new ClavenarDenied(
            "send_email",
            List.of("indirect prompt injection"),
            List.of(),
            "Exfiltration",
            "brain",
            "abc-123",
            detail);
    String p = DevMode.renderDenyPanel(d);
    for (String want :
        List.of(
            "send_email", "layer=brain", "correlation=abc-123", "injection", "0.91", "⚠ flagged",
            "degraded: injection")) {
      assertTrue(p.contains(want), "panel missing '" + want + "':\n" + p);
    }
  }

  @Test
  void hintsToEnableVerboseVerdictsWhenDetailAbsent() {
    ClavenarDenied d =
        new ClavenarDenied(
            "wire_transfer", List.of("policy denied"), List.of(), "Direct Execution", null, null);
    String p = DevMode.renderDenyPanel(d);
    assertTrue(p.contains("wire_transfer"), p);
    assertTrue(p.contains("CLAVENAR_PROXY_VERBOSE_VERDICTS"), p);
    assertFalse(p.contains("⚠ flagged"), p);
  }
}
