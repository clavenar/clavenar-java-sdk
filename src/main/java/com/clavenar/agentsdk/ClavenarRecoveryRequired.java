package com.clavenar.agentsdk;

import java.io.Serial;

/** A durable intent exists but its provider effect cannot yet be conclusively reconciled. */
public final class ClavenarRecoveryRequired extends ClavenarException {
  @Serial private static final long serialVersionUID = 1L;

  private final String idempotencyId;

  public ClavenarRecoveryRequired(String idempotencyId) {
    super("clavenar execution " + idempotencyId + " requires provider reconciliation");
    this.idempotencyId = idempotencyId;
  }

  public String idempotencyId() {
    return idempotencyId;
  }
}
