package com.clavenar.agentsdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * Configuration for inspection. Build with {@link #builder(String)}. Endpoint is required; the rest
 * default to enforce mode, a 10s per-request timeout, and 3 retries at a 100ms base delay.
 */
public final class ClavenarOptions {
  private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder().build();

  private final String endpoint;
  private final String token;
  private final Mode mode;
  private final Duration timeout;
  private final RetryOptions retry;
  private final HttpClient httpClient;
  private final BiConsumer<Verdict, VerdictContext> onVerdict;
  private final BiConsumer<ClavenarTransportException, VerdictContext> onPolicyError;

  private ClavenarOptions(Builder b) {
    this.endpoint = b.endpoint;
    this.token = b.token;
    this.mode = b.mode;
    this.timeout = b.timeout;
    this.retry = b.retry;
    this.httpClient = b.httpClient;
    this.onVerdict = b.onVerdict;
    this.onPolicyError = b.onPolicyError;
  }

  public static Builder builder(String endpoint) {
    return new Builder(endpoint);
  }

  public String endpoint() {
    return endpoint;
  }

  public String token() {
    return token;
  }

  public Mode mode() {
    return mode;
  }

  public Duration timeout() {
    return timeout;
  }

  public RetryOptions retry() {
    return retry;
  }

  /** The configured client, or a shared default when none was set. */
  public HttpClient httpClient() {
    return httpClient != null ? httpClient : DEFAULT_CLIENT;
  }

  public BiConsumer<Verdict, VerdictContext> onVerdict() {
    return onVerdict;
  }

  public BiConsumer<ClavenarTransportException, VerdictContext> onPolicyError() {
    return onPolicyError;
  }

  void validate() {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new ClavenarConfigException("clavenar: endpoint is required");
    }
    try {
      URI u = URI.create(endpoint);
      if (u.getScheme() == null || u.getHost() == null) {
        throw new ClavenarConfigException(
            "clavenar: endpoint is not a valid absolute URL: " + endpoint);
      }
    } catch (IllegalArgumentException e) {
      throw new ClavenarConfigException("clavenar: endpoint is not a valid URL: " + endpoint);
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new ClavenarConfigException("clavenar: timeout must be positive");
    }
  }

  /** Fluent builder for {@link ClavenarOptions}. */
  public static final class Builder {
    private final String endpoint;
    private String token;
    private Mode mode = Mode.ENFORCE;
    private Duration timeout = Duration.ofSeconds(10);
    private RetryOptions retry = RetryOptions.defaults();
    private HttpClient httpClient;
    private BiConsumer<Verdict, VerdictContext> onVerdict;
    private BiConsumer<ClavenarTransportException, VerdictContext> onPolicyError;

    private Builder(String endpoint) {
      this.endpoint = endpoint;
    }

    public Builder token(String token) {
      this.token = token;
      return this;
    }

    public Builder mode(Mode mode) {
      this.mode = mode;
      return this;
    }

    /** Shorthand for {@code mode(Mode.OBSERVE)}. */
    public Builder observe() {
      this.mode = Mode.OBSERVE;
      return this;
    }

    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public Builder retry(RetryOptions retry) {
      this.retry = retry;
      return this;
    }

    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public Builder onVerdict(BiConsumer<Verdict, VerdictContext> onVerdict) {
      this.onVerdict = onVerdict;
      return this;
    }

    public Builder onPolicyError(
        BiConsumer<ClavenarTransportException, VerdictContext> onPolicyError) {
      this.onPolicyError = onPolicyError;
      return this;
    }

    public ClavenarOptions build() {
      return new ClavenarOptions(this);
    }
  }
}
