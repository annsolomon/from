package in.dexter.identity.domain;

import in.dexter.core.error.FieldError;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

/** Validation of the editable profile fields (PATCH /v1/me). */
public final class Profiles {
  private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");
  private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");
  public static final int DISPLAY_NAME_MAX = 80;

  private Profiles() {}

  /** A region-based IANA zone ({@code Asia/Kolkata}); fixed offsets and abbreviations are refused. */
  public static FieldError checkTz(String field, String tz) {
    if (tz == null || tz.isBlank() || !tz.contains("/") || tz.length() > 64) {
      return new FieldError(field, "INVALID_TZ", "Use an IANA time zone such as Asia/Kolkata");
    }
    try {
      ZoneId.of(tz);
      return null;
    } catch (DateTimeException e) {
      return new FieldError(field, "INVALID_TZ", "Unknown time zone");
    }
  }

  public static FieldError checkDisplayName(String field, String name) {
    if (name == null) {
      return null;
    }
    String s = name.strip();
    int len = s.codePointCount(0, s.length());
    if (len < 1 || len > DISPLAY_NAME_MAX) {
      return new FieldError(field, "INVALID_LENGTH", "Use 1 to " + DISPLAY_NAME_MAX + " characters");
    }
    if (s.chars().anyMatch(Character::isISOControl)) {
      return new FieldError(field, "INVALID_CHARACTERS", "Control characters are not allowed");
    }
    return null;
  }

  /**
   * E.164 form ({@code +919876543210}) after removing spaces, dots, dashes and brackets; a bare 10-digit
   * Indian mobile number gets {@code +91}. Null when it cannot be an E.164 number.
   */
  public static String normalizePhone(String raw) {
    if (raw == null) {
      return null;
    }
    String p = SEPARATORS.matcher(raw.strip()).replaceAll("");
    if (p.matches("[6-9][0-9]{9}")) {
      p = "+91" + p;
    }
    return E164.matcher(p).matches() ? p : null;
  }

  /** {@code +91******3210} for display. */
  public static String maskPhone(String e164) {
    if (e164 == null || e164.length() < 7) {
      return e164;
    }
    int keep = 4;
    String cc = e164.startsWith("+91") ? "+91" : e164.substring(0, 2);
    return cc + "*".repeat(e164.length() - cc.length() - keep) + e164.substring(e164.length() - keep);
  }

  public static List<FieldError> nonNull(FieldError... errors) {
    return java.util.Arrays.stream(errors).filter(java.util.Objects::nonNull).toList();
  }
}
