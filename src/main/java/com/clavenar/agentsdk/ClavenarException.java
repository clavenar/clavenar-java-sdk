package com.clavenar.agentsdk;

import java.io.Serial;

/**
 * Root of the SDK's exceptions, so callers can {@code catch (ClavenarException e)} at a tool
 * boundary. All concrete types are unchecked.
 */
public abstract class ClavenarException extends RuntimeException {
  @Serial private static final long serialVersionUID = 1L;

  protected ClavenarException(String message) {
    super(message);
  }

  protected ClavenarException(String message, Throwable cause) {
    super(message, cause);
  }
}
