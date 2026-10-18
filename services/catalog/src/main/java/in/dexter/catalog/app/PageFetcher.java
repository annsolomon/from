package in.dexter.catalog.app;

import java.net.URI;

/**
 * The collector's network port (MASTER_PLAN 7.3): an SSRF-safe GET. Implementations check the URL and every
 * resolved address before connecting, follow at most a few redirects (each hop checked again), cap size and time,
 * and check the content type against the source kind.
 */
public interface PageFetcher {
  /** What a source kind accepts. */
  enum Expect { HTML, PDF, IMAGE, XML }

  record Fetched(URI url, String contentType, byte[] body) {}

  /** A refused or failed fetch; {@code code} is a {@code FetchPolicy.Refusal} name or a transport code. */
  final class FetchFailure extends RuntimeException {
    private final String code;

    public FetchFailure(String code, String detail) {
      super(code + ": " + detail);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  Fetched fetch(URI url, Expect expect);
}
