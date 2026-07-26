package com.clavenar.agentsdk;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Reusable reload-before-request mTLS, token, deadline, and proxy profile. */
public final class SecureTransportProfile {
  /** Explicit proxy behavior; environment variables are consulted only in ENVIRONMENT mode. */
  public enum ProxyMode {
    DIRECT,
    ENVIRONMENT,
    EXPLICIT
  }

  private final Path caBundle;
  private final Path clientCertificate;
  private final Path privateKey;
  private final Supplier<String> tokenSource;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final ProxyMode proxyMode;
  private final URI proxyUri;

  private SecureTransportProfile(Builder builder) {
    this.caBundle = builder.caBundle;
    this.clientCertificate = builder.clientCertificate;
    this.privateKey = builder.privateKey;
    this.tokenSource = builder.tokenSource;
    this.connectTimeout = builder.connectTimeout;
    this.requestTimeout = builder.requestTimeout;
    this.proxyMode = builder.proxyMode;
    this.proxyUri = builder.proxyUri;
    validate();
  }

  public static Builder builder(Path caBundle, Path clientCertificate, Path privateKey) {
    return new Builder(caBundle, clientCertificate, privateKey);
  }

  /** Build a complete fresh TLS client snapshot from the current source files. */
  public HttpClient client() {
    try {
      CertificateFactory certificates = CertificateFactory.getInstance("X.509");
      List<Certificate> chain =
          new ArrayList<>(
              certificates.generateCertificates(
                  new ByteArrayInputStream(readRequired(clientCertificate, "client certificate"))));
      if (chain.isEmpty()) {
        throw new ClavenarConfigException(
            "secure transport client certificate contains no certificates");
      }
      PrivateKey key = parsePrivateKey(readRequired(privateKey, "private key"));

      KeyStore identity = KeyStore.getInstance("PKCS12");
      identity.load(null, null);
      identity.setKeyEntry("client", key, new char[0], chain.toArray(Certificate[]::new));
      KeyManagerFactory keyManagers =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagers.init(identity, new char[0]);

      KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
      trust.load(null, null);
      int index = 0;
      for (Certificate certificate :
          certificates.generateCertificates(
              new ByteArrayInputStream(readRequired(caBundle, "CA bundle")))) {
        trust.setCertificateEntry("ca-" + index++, certificate);
      }
      if (index == 0) {
        throw new ClavenarConfigException("secure transport CA bundle contains no certificates");
      }
      TrustManagerFactory trustManagers =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(trust);

      SSLContext tls = SSLContext.getInstance("TLS");
      tls.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), new SecureRandom());
      HttpClient.Builder client =
          HttpClient.newBuilder().sslContext(tls).connectTimeout(connectTimeout);
      ProxySelector selector = proxySelector();
      if (selector != null) {
        client.proxy(selector);
      }
      return client.build();
    } catch (ClavenarConfigException error) {
      throw error;
    } catch (Exception error) {
      throw new ClavenarConfigException(
          "cannot build secure transport profile: " + error.getMessage());
    }
  }

  /** Acquire the current token for one request. */
  public String token() {
    if (tokenSource == null) {
      return null;
    }
    String token = tokenSource.get();
    if (token == null || token.strip().isEmpty()) {
      throw new ClavenarConfigException("secure transport token source returned an empty token");
    }
    return token.strip();
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  private void validate() {
    if (connectTimeout == null
        || requestTimeout == null
        || connectTimeout.isZero()
        || requestTimeout.isZero()
        || connectTimeout.isNegative()
        || requestTimeout.isNegative()) {
      throw new ClavenarConfigException("secure transport timeouts must be positive");
    }
    if (proxyMode == ProxyMode.EXPLICIT
        && (proxyUri == null
            || proxyUri.getHost() == null
            || (!"http".equals(proxyUri.getScheme()) && !"https".equals(proxyUri.getScheme())))) {
      throw new ClavenarConfigException(
          "secure transport explicit proxy must use an absolute HTTP(S) URL");
    }
  }

  private ProxySelector proxySelector() {
    if (proxyMode == ProxyMode.DIRECT) {
      return new DirectProxySelector();
    }
    URI selected = proxyUri;
    if (proxyMode == ProxyMode.ENVIRONMENT) {
      String value = System.getenv("HTTPS_PROXY");
      if (value == null || value.isBlank()) {
        value = System.getenv("HTTP_PROXY");
      }
      if (value == null || value.isBlank()) {
        return new DirectProxySelector();
      }
      selected = URI.create(value);
    }
    if (selected == null) {
      return null;
    }
    int port = selected.getPort() >= 0 ? selected.getPort() : 80;
    return ProxySelector.of(new InetSocketAddress(selected.getHost(), port));
  }

  private static byte[] readRequired(Path path, String label) throws Exception {
    if (path == null || !Files.isRegularFile(path)) {
      throw new ClavenarConfigException("secure transport " + label + " is missing");
    }
    byte[] value = Files.readAllBytes(path);
    if (value.length == 0) {
      throw new ClavenarConfigException("secure transport " + label + " is empty");
    }
    return value;
  }

  private static PrivateKey parsePrivateKey(byte[] pem) throws Exception {
    String text = new String(pem, StandardCharsets.US_ASCII);
    String encoded =
        text.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(encoded);
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (Exception ignored) {
        // Try the next supported PKCS#8 algorithm.
      }
    }
    throw new ClavenarConfigException("unsupported secure transport PKCS#8 private key");
  }

  /** Builder for one immutable profile. */
  public static final class Builder {
    private final Path caBundle;
    private final Path clientCertificate;
    private final Path privateKey;
    private Supplier<String> tokenSource;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private ProxyMode proxyMode = ProxyMode.DIRECT;
    private URI proxyUri;

    private Builder(Path caBundle, Path clientCertificate, Path privateKey) {
      this.caBundle = caBundle;
      this.clientCertificate = clientCertificate;
      this.privateKey = privateKey;
    }

    public Builder tokenSource(Supplier<String> tokenSource) {
      this.tokenSource = tokenSource;
      return this;
    }

    public Builder timeouts(Duration connectTimeout, Duration requestTimeout) {
      this.connectTimeout = connectTimeout;
      this.requestTimeout = requestTimeout;
      return this;
    }

    public Builder proxy(ProxyMode mode, URI uri) {
      this.proxyMode = mode;
      this.proxyUri = uri;
      return this;
    }

    public SecureTransportProfile build() {
      return new SecureTransportProfile(this);
    }
  }

  private static final class DirectProxySelector extends ProxySelector {
    @Override
    public List<Proxy> select(URI uri) {
      return List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) {
      // A direct connection has no proxy endpoint to report.
    }
  }
}
