package in.dexter.web.etag;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strong ETags carrying the row {@code version}: {@code "3"}. */
public final class ETags {
  private static final Pattern STRONG = Pattern.compile("\"([0-9]{1,18})\"");

  private ETags() {}

  public static String of(long version) {
    return "\"" + version + "\"";
  }

  /** Parses an If-Match value into a version (strong comparison only, one tag, no wildcard). */
  public static long parseIfMatch(String header) {
    if (header == null || header.isBlank()) {
      throw new DexterException(CoreErrors.IF_MATCH_REQUIRED, "If-Match header with the current ETag is required");
    }
    Matcher m = STRONG.matcher(header.trim());
    if (!m.matches()) {
      throw new DexterException(CoreErrors.IF_MATCH_INVALID, "If-Match must be a single strong ETag like \"3\"");
    }
    return Long.parseLong(m.group(1));
  }
}
