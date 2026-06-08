package com.clavenar.agentsdk;

/** Thrown for malformed configuration, or a model tool call with unparseable arguments. */
public final class ClavenarConfigException extends ClavenarException {
  public ClavenarConfigException(String message) {
    super(message);
  }
}
