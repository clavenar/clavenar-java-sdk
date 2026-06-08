package com.clavenar.agentsdk;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Minimal in-process HTTP server for wire tests, built on the JDK's HttpServer (no test deps). */
final class TestServer implements AutoCloseable {
  final String baseUrl;
  private final HttpServer server;

  TestServer(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] reqBody = exchange.getRequestBody().readAllBytes();
          Response r =
              handler.handle(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  new String(reqBody, StandardCharsets.UTF_8),
                  exchange.getRequestHeaders());
          if (r.correlationId != null) {
            exchange.getResponseHeaders().add("X-Clavenar-Correlation-Id", r.correlationId);
          }
          byte[] body = r.body == null ? new byte[0] : r.body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(r.status, body.length == 0 ? -1 : body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  @FunctionalInterface
  interface Handler {
    Response handle(String method, String path, String body, Headers headers);
  }

  static final class Response {
    int status;
    String body;
    String correlationId;

    static Response of(int status, String body) {
      Response r = new Response();
      r.status = status;
      r.body = body;
      return r;
    }

    Response corr(String id) {
      this.correlationId = id;
      return this;
    }
  }
}
