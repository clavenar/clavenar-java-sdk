package com.clavenar.agentsdk;

/**
 * Root of the SDK's exceptions, so callers can {@code catch (ClavenarException e)} at a tool
 * boundary. All concrete types are unchecked.
 */
public abstract class ClavenarException extends RuntimeException {
  protected ClavenarException(String message) {
    super(message);
  }
}
