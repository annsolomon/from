package in.dexter.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email normalisation and a deliberately simple shape check (RFC 5321 lengths, one {@code @}, a dot in the
 * domain). The real proof of an address is the verification email. Stored lower-case; the column is citext.
 */
public final class Emails {
  private static final Pattern SHAPE = Pattern.compile("[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+");
  public static final int MAX_LENGTH = 254;

  private Emails() {}

  /** Trimmed lower-case form, or null when the input is not a plausible address. */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String e = raw.strip().toLowerCase(Locale.ROOT);
    if (e.isEmpty() || e.length() > MAX_LENGTH || !SHAPE.matcher(e).matches()) {
      return null;
    }
    return e;
  }

  /** {@code a***@example.com} for logs and admin views. */
  public static String mask(String email) {
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
