package in.dexter.core.request;

import in.dexter.core.id.Ids;
import java.util.regex.Pattern;

/** X-Request-Id rules (MASTER_PLAN 9): accept a sane client id, otherwise generate one. */
public final class RequestIds {
  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:\\-]{1,128}");

  private RequestIds() {}

  public static boolean isAcceptable(String candidate) {
    return candidate != null && SAFE.matcher(candidate).matches();
  }

  /** The client id if it is safe to echo and log, otherwise a fresh UUIDv7 string. */
  public static String acceptOrGenerate(String candidate) {
    return isAcceptable(candidate) ? candidate : Ids.newId().toString();
  }
}
