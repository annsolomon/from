package in.dexter.cache;

import org.jspecify.annotations.Nullable;

/**
 * The stored string: {@code <loadMillis>:J<json>} for a value, {@code <loadMillis>:N} for the
 * NOT_FOUND marker (negative caching). {@code loadMillis} is how long the DB load took; the early-refresh
 * decision (XFetch) scales with it.
 */
record CacheValue(long loadMillis, @Nullable String json) {
  boolean notFound() {
    return json == null;
  }

  String encode() {
    return loadMillis + ":" + (json == null ? "N" : "J" + json);
  }

  /** Null when the string is not in this format (treated as a miss and overwritten). */
  static @Nullable CacheValue decode(@Nullable String s) {
    if (s == null) {
      return null;
    }
    int colon = s.indexOf(':');
    if (colon < 1 || colon + 1 >= s.length()) {
      return null;
    }
    long ms;
    try {
      ms = Long.parseLong(s, 0, colon, 10);
    } catch (NumberFormatException e) {
      return null;
    }
    if (ms < 0) {
      return null;
    }
    char kind = s.charAt(colon + 1);
    if (kind == 'N' && colon + 2 == s.length()) {
      return new CacheValue(ms, null);
    }
    if (kind == 'J') {
      return new CacheValue(ms, s.substring(colon + 2));
    }
    return null;
  }
}
