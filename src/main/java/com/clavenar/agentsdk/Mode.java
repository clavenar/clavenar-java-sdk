package com.clavenar.agentsdk;

/** Whether a deny / pending verdict blocks the agent. */
public enum Mode {
  /**
   * Deny throws {@link ClavenarDenied}, pending throws {@link ClavenarPending}; a transport failure
   * fails closed.
   */
  ENFORCE,
  /** Nothing blocks; verdicts surface via callbacks and the call passes through. */
  OBSERVE
}
