package in.dexter.catalog.infra;

import in.dexter.catalog.app.PageFetcher;
import in.dexter.catalog.domain.FetchPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The SSRF-safe fetcher (MASTER_PLAN 7.3): before every hop the URL passes {@link FetchPolicy} and every address its
 * host resolves to must be public; redirects are followed by hand (the client never follows one), at most
 * {@code maxRedirects}, each target checked again; the body is read up to {@code maxBytes} (larger = refused, not
 * truncated) within {@code timeout} overall; the content type must fit the source kind. Requests carry no cookies or
 * credentials and identify the bot. Java resolves the host again when it connects, from the same JVM address cache
 * the check just filled, so a DNS answer cannot change between check and connect while the cache entry lives
 * (ADR-0015).
 */
public final class SafeFetcher implements PageFetcher {
  /** DNS resolution (tests map invented hosts). */
  public interface Resolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
  }

  public static final String USER_AGENT = "DexterCatalogBot/1.0 (+https://dexter.in/bot)";
  public static final Resolver SYSTEM = host -> Arrays.asList(InetAddress.getAllByName(host));

  private final FetchPolicy policy;
  private final Resolver resolver;
  private final int maxBytes;
  private final Duration timeout;
  private final int maxRedirects;
  private final HttpClient http;

  public SafeFetcher(FetchPolicy policy, Resolver resolver, int maxBytes, Duration timeout, int maxRedirects) {
    this.policy = policy;
    this.resolver = resolver == null ? SYSTEM : resolver;
    this.maxBytes = maxBytes;
    this.timeout = timeout;
    this.maxRedirects = maxRedirects;
    this.http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(timeout.compareTo(Duration.ofSeconds(5)) < 0 ? timeout : Duration.ofSeconds(5))
        .build();
  }

  @Override
  public Fetched fetch(URI start, Expect expect) {
    long deadline = System.nanoTime() + timeout.toNanos();
    URI url = start;
    for (int hop = 0; hop <= maxRedirects; hop++) {
      check(url);
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        throw new FetchFailure("TIMEOUT", "time cap reached");
      }
      HttpRequest req = HttpRequest.newBuilder(url)
          .timeout(Duration.ofNanos(left))
          .header("User-Agent", USER_AGENT)
          .header("Accept", accept(expect))
          .GET().build();
      HttpResponse<InputStream> r;
      try {
        r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
      } catch (HttpTimeoutException e) {
        throw new FetchFailure("TIMEOUT", e.getMessage());
      } catch (IOException e) {
        throw new FetchFailure("NETWORK", e.toString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new FetchFailure("INTERRUPTED", "interrupted");
      }
      int status = r.statusCode();
      if (status >= 300 && status < 400 && status != 304) {
        close(r);
        Optional<String> loc = r.headers().firstValue("location");
        if (loc.isEmpty()) {
          throw new FetchFailure("HTTP_" + status, "redirect without Location");
        }
        try {
          url = url.resolve(loc.get().strip());
        } catch (IllegalArgumentException e) {
          throw new FetchFailure("BAD_URL", "bad redirect target");
        }
        continue;
      }
      if (status != 200) {
        close(r);
        throw new FetchFailure("HTTP_" + status, "status " + status);
      }
      String type = r.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
      if (!fits(type, expect)) {
        close(r);
        throw new FetchFailure("WRONG_CONTENT_TYPE", type.isEmpty() ? "none" : type);
      }
      long declared = r.headers().firstValueAsLong("content-length").orElse(-1);
      if (declared > maxBytes) {
        close(r);
        throw new FetchFailure("TOO_LARGE", declared + " bytes");
      }
      return new Fetched(url, type, read(r.body(), deadline));
    }
    throw new FetchFailure("TOO_MANY_REDIRECTS", "more than " + maxRedirects);
  }

  private void check(URI url) {
    Optional<FetchPolicy.Refusal> refused = policy.checkUrl(url);
    if (refused.isPresent()) {
      throw new FetchFailure(refused.get().name(), url.toString());
    }
    List<InetAddress> addresses;
    try {
      addresses = resolver.resolve(url.getHost());
    } catch (UnknownHostException e) {
      throw new FetchFailure("UNKNOWN_HOST", url.getHost());
    }
    refused = policy.checkAddresses(url, addresses);
    if (refused.isPresent()) {
      throw new FetchFailure(refused.get().name(), url.getHost());
    }
  }

  private byte[] read(InputStream in, long deadline) {
    try (in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[16_384];
      int n;
      while ((n = in.read(buf)) >= 0) {
        if (out.size() + n > maxBytes) {
          throw new FetchFailure("TOO_LARGE", "more than " + maxBytes + " bytes");
        }
        if (System.nanoTime() > deadline) {
          throw new FetchFailure("TIMEOUT", "time cap reached while reading");
        }
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new FetchFailure("NETWORK", e.toString());
    }
  }

  private static void close(HttpResponse<InputStream> r) {
    try {
      r.body().close();
    } catch (IOException e) {
      // nothing to do
    }
  }

  static String accept(Expect e) {
    return switch (e) {
      case HTML -> "text/html,application/xhtml+xml";
      case PDF -> "application/pdf";
      case IMAGE -> "image/*";
      case XML -> "application/xml,text/xml";
    };
  }

  static boolean fits(String type, Expect e) {
    return switch (e) {
      case HTML -> type.startsWith("text/html") || type.startsWith("application/xhtml+xml");
      case PDF -> type.startsWith("application/pdf");
      case IMAGE -> type.startsWith("image/") && !type.startsWith("image/svg");
      case XML -> type.startsWith("application/xml") || type.startsWith("text/xml");
    };
  }
}
