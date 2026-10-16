package in.dexter.core.log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks PII and secrets in log text (MASTER_PLAN 7.4): emails, Indian phone numbers, JWTs, bearer
 * tokens, card-number-like digit runs (13-19 digits, spaces/dashes allowed) and long opaque tokens.
 * Pure function, safe to call on every log line.
 */
public final class PiiMasker {
  private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");
  private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern EMAIL = Pattern.compile("\\b([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");
  private static final Pattern CARD = Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])");
  private static final Pattern PHONE = Pattern.compile("(?<![0-9])(?:\\+?91[ -]?|0)?[6-9][0-9]{4}[ -]?[0-9]{5}(?![0-9])");
  private static final Pattern SECRET_KV =
      Pattern.compile("(?i)\\b(password|passwd|secret|token|refresh_token|refreshToken|otp|cvv)(\\s*[=:]\\s*\"?)([^\\s\",&]+)");

  private PiiMasker() {}

  public static String mask(String in) {
    if (in == null || in.isEmpty()) {
      return in;
    }
    String s = JWT.matcher(in).replaceAll("[JWT]");
    s = BEARER.matcher(s).replaceAll("$1 [TOKEN]");
    s = SECRET_KV.matcher(s).replaceAll("$1$2[REDACTED]");
    s = EMAIL.matcher(s).replaceAll("$1***@$2");
    s = replace(CARD, s, m -> {
      String digits = m.replaceAll("[^0-9]", "");
      return "[CARD-" + digits.substring(digits.length() - 4) + "]";
    });
    s = replace(PHONE, s, m -> {
      String digits = m.replaceAll("[^0-9]", "");
      return "[PHONE-" + digits.substring(digits.length() - 2) + "]";
    });
    return s;
  }

  private static String replace(Pattern p, String s, java.util.function.UnaryOperator<String> f) {
    Matcher m = p.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m.group())));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
