package com.clavenar.agentsdk;

import java.io.Serial;

/** Thrown for malformed configuration, or a model tool call with unparseable arguments. */
public final class ClavenarConfigException extends ClavenarException {
  @Serial private static final long serialVersionUID = 1L;

  public ClavenarConfigException(String message) {
    super(message);
  }
}
