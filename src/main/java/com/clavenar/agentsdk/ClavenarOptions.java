package com.clavenar.agentsdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;

/**
 * Configuration for inspection. Build with {@link #builder(String)}. Endpoint is required; the rest
 * default to enforce mode, a 10s per-request timeout, and 3 retries at a 100ms base delay.
 */
public final class ClavenarOptions {
  private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder().build();
  private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(1);

  private final String endpoint;
  private final String token;
  private final Mode mode;
  private final Duration timeout;
  private final RetryOptions retry;
  private final HttpClient httpClient;
  private final SecureTransportProfile secureTransport;
  private final BiConsumer<Verdict, VerdictContext> onVerdict;
  private final BiConsumer<ClavenarTransportException, VerdictContext> onPolicyError;
  private final boolean devMode;
  private final boolean allowUninspectedStream;
  private final boolean allowInsecureLoopback;
  private final Executor asyncExecutor;

  private ClavenarOptions(Builder b) {
    this.endpoint = b.endpoint;
    this.token = b.token;
    this.mode = b.mode;
    this.timeout = b.secureTransport != null ? b.secureTransport.requestTimeout() : b.timeout;
    this.retry = b.retry;
    this.httpClient = b.httpClient;
    this.secureTransport = b.secureTransport;
    this.onVerdict = b.onVerdict;
    this.onPolicyError = b.onPolicyError;
    this.devMode = b.devMode;
    this.allowUninspectedStream = b.allowUninspectedStream;
    this.allowInsecureLoopback = b.allowInsecureLoopback;
    this.asyncExecutor = b.asyncExecutor;
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
    return secureTransport != null
        ? secureTransport.client()
        : (httpClient != null ? httpClient : DEFAULT_CLIENT);
  }

  String effectiveToken() {
    return secureTransport != null ? secureTransport.token() : token;
  }

  public BiConsumer<Verdict, VerdictContext> onVerdict() {
    return onVerdict;
  }

  public BiConsumer<ClavenarTransportException, VerdictContext> onPolicyError() {
    return onPolicyError;
  }

  /**
   * Developer mode: render the gateway's verbose-verdict detail to stderr on a denied call before
   * throwing. Off by default. Dev/staging only — detailed denials are an attacker oracle.
   */
  public boolean devMode() {
    return devMode;
  }

  /**
   * Let {@link Clavenar#wrap} pass streaming calls through uninspected instead of throwing. Off by
   * default — the explicit, dangerous opt-out shared with the TypeScript wrapper.
   */
  public boolean allowUninspectedStream() {
    return allowUninspectedStream;
  }

  Executor asyncExecutor() {
    return asyncExecutor;
  }

  void validate() {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new ClavenarConfigException("clavenar: endpoint is required");
    }
    try {
      URI u = URI.create(endpoint);
      String scheme = u.getScheme();
      if ((!"http".equals(scheme) && !"https".equals(scheme))
          || u.getHost() == null
          || u.getUserInfo() != null
          || u.getQuery() != null
          || u.getFragment() != null) {
        throw new ClavenarConfigException(
            "clavenar: endpoint must be an absolute HTTP(S) URL without credentials, query, or"
                + " fragment: "
                + endpoint);
      }
      boolean hasCredentials = token != null || secureTransport != null;
      if (hasCredentials && !"https".equals(scheme)) {
        boolean loopback = "127.0.0.1".equals(u.getHost()) || "[::1]".equals(u.getHost());
        if (!allowInsecureLoopback || !loopback) {
          throw new ClavenarConfigException(
              "clavenar: credentials require HTTPS; insecure transport is allowed only for an"
                  + " explicit loopback development endpoint");
        }
      }
    } catch (IllegalArgumentException e) {
      throw new ClavenarConfigException("clavenar: endpoint is not a valid URL: " + endpoint);
    }
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new ClavenarConfigException("clavenar: timeout must be in (0, 5 minutes]");
    }
    if (secureTransport != null && (httpClient != null || token != null)) {
      throw new ClavenarConfigException(
          "clavenar: secure transport cannot be combined with token or httpClient");
    }
    if (token != null
        && (token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)) {
      throw new ClavenarConfigException("clavenar: token must be non-empty and single-line");
    }
    if (mode == null || retry == null || asyncExecutor == null) {
      throw new ClavenarConfigException("clavenar: mode, retry, and asyncExecutor are required");
    }
    if (retry.maxAttempts() < 1 || retry.maxAttempts() > 10) {
      throw new ClavenarConfigException("clavenar: retry.maxAttempts must be in [1, 10]");
    }
    if (retry.baseDelay() == null
        || retry.baseDelay().isNegative()
        || retry.baseDelay().compareTo(MAX_RETRY_DELAY) > 0) {
      throw new ClavenarConfigException("clavenar: retry.baseDelay must be in [0, 1 minute]");
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
    private SecureTransportProfile secureTransport;
    private BiConsumer<Verdict, VerdictContext> onVerdict;
    private BiConsumer<ClavenarTransportException, VerdictContext> onPolicyError;
    private boolean devMode;
    private boolean allowUninspectedStream;
    private boolean allowInsecureLoopback;
    private Executor asyncExecutor = ForkJoinPool.commonPool();

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

    public Builder secureTransport(SecureTransportProfile secureTransport) {
      this.secureTransport = secureTransport;
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

    /**
     * Render the gateway's verbose-verdict detail to stderr on a denied call. Dev/staging only —
     * detailed denials are an attacker oracle.
     */
    public Builder devMode(boolean devMode) {
      this.devMode = devMode;
      return this;
    }

    /**
     * Let {@link Clavenar#wrap} pass streaming calls through uninspected instead of throwing.
     * Dangerous: streamed tool calls skip policy entirely — prefer {@link StreamGate}.
     */
    public Builder allowUninspectedStream(boolean allowUninspectedStream) {
      this.allowUninspectedStream = allowUninspectedStream;
      return this;
    }

    /** Permit credentials over HTTP only for an explicit 127.0.0.1 / ::1 DEV endpoint. */
    public Builder allowInsecureLoopback(boolean allowInsecureLoopback) {
      this.allowInsecureLoopback = allowInsecureLoopback;
      return this;
    }

    /** Executor used by {@link ClavenarInspector}'s asynchronous methods. */
    public Builder asyncExecutor(Executor asyncExecutor) {
      this.asyncExecutor = asyncExecutor;
      return this;
    }

    public ClavenarOptions build() {
      return new ClavenarOptions(this);
    }
  }
}
