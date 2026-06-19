package com.clavenar.agentsdk;

import java.util.List;

/**
 * The verbose-verdict per-detector breakdown attached to a deny when the gateway runs with {@code
 * CLAVENAR_PROXY_VERBOSE_VERDICTS=true}. Absent (null on the deny) otherwise.
 */
public final class VerdictDetail {
  private final List<DetectorScore> detectors;
  private final List<String> degraded;

  VerdictDetail(List<DetectorScore> detectors, List<String> degraded) {
    this.detectors = detectors == null ? List.of() : List.copyOf(detectors);
    this.degraded = degraded == null ? List.of() : List.copyOf(degraded);
  }

  public List<DetectorScore> detectors() {
    return detectors;
  }

  /** Detector lanes that served a fallback verdict. */
  public List<String> degraded() {
    return degraded;
  }

  /**
   * One detector's contribution. {@code score} is the numeric signal in [0,1]; {@code flagged} is
   * the boolean verdict on the boolean lanes (injection / malicious_code / compromised_package) and
   * false on the numeric lanes (persona_drift / sequence_escalation), where {@code score} is read.
   */
  public static final class DetectorScore {
    private final String detector;
    private final double score;
    private final boolean flagged;

    DetectorScore(String detector, double score, boolean flagged) {
      this.detector = detector;
      this.score = score;
      this.flagged = flagged;
    }

    public String detector() {
      return detector;
    }

    public double score() {
      return score;
    }

    public boolean flagged() {
      return flagged;
    }
  }
}
