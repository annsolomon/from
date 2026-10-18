package in.dexter.catalog.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local issuer web site for the ingestion ITs (JDK HttpServer on 127.0.0.1, random port): pages are set per path
 * and change during a test; every request is recorded (path + time) so tests can count fetches and measure the
 * politeness gap. Its {@code 127.0.0.1:port} is the collector's one dev host (http, private address allowed).
 */
public final class MockIssuerSite {
  /** A canned response. */
  public record Page(int status, String contentType, byte[] body, Map<String, String> headers) {
    public static Page html(String html) {
      return new Page(200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public static Page of(String contentType, String body) {
      return new Page(200, contentType, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public static Page redirect(String location) {
      return new Page(302, null, new byte[0], Map.of("Location", location));
    }
  }

  public record Hit(String path, Instant at) {}

  private final HttpServer server;
  private final Map<String, Page> pages = new ConcurrentHashMap<>();
  private final List<Hit> hits = new CopyOnWriteArrayList<>();

  public MockIssuerSite() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", x -> {
      String path = x.getRequestURI().getPath();
      hits.add(new Hit(path, Instant.now()));
      Page p = pages.get(path);
      if (p == null) {
        x.sendResponseHeaders(404, -1);
        x.close();
        return;
      }
      if (p.contentType() != null) {
        x.getResponseHeaders().add("Content-Type", p.contentType());
      }
      p.headers().forEach((k, v) -> x.getResponseHeaders().add(k, v));
      x.sendResponseHeaders(p.status(), p.body().length == 0 ? -1 : p.body().length);
      if (p.body().length > 0) {
        try (OutputStream o = x.getResponseBody()) {
          o.write(p.body());
        }
      }
      x.close();
    });
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  /** {@code 127.0.0.1:port}: the dev-host entry. */
  public String hostPort() {
    return "127.0.0.1:" + port();
  }

  public String url(String path) {
    return "http://" + hostPort() + path;
  }

  public void put(String path, Page page) {
    pages.put(path, page);
  }

  public List<Hit> hits(String path) {
    List<Hit> out = new ArrayList<>();
    for (Hit h : hits) {
      if (h.path().equals(path)) {
        out.add(h);
      }
    }
    return out;
  }

  public List<Hit> allHits() {
    return List.copyOf(hits);
  }
}
