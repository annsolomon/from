package in.dexter.security.web;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** RFC 6750 header parsing. Only the {@code Authorization} header is accepted (no query or form tokens). */
public final class BearerTokens {
  public static final String HEADER = "Authorization";
  private static final String PREFIX = "bearer ";

  private BearerTokens() {}

  /** The token, or null when the header is absent or not a single Bearer credential. */
  public static @Nullable String extract(@Nullable String header) {
    if (header == null || header.length() <= PREFIX.length()) {
      return null;
    }
    if (!header.substring(0, PREFIX.length()).toLowerCase(Locale.ROOT).equals(PREFIX)) {
      return null;
    }
    String token = header.substring(PREFIX.length()).strip();
    if (token.isEmpty() || token.indexOf(' ') >= 0 || token.indexOf(',') >= 0) {
      return null;
    }
    return token;
  }
}
