package in.dexter.identity.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One-time recovery codes (MASTER_PLAN 7.1: 10 per account): {@code xxxxx-xxxxx} from a 31-character alphabet
 * without look-alikes (≈ 49.5 bits each). Stored only as a keyed hash of the normalised form.
 */
public final class RecoveryCodes {
  public static final int COUNT = 10;
  private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private RecoveryCodes() {}

  public static List<String> generate() {
    List<String> out = new ArrayList<>(COUNT);
    for (int i = 0; i < COUNT; i++) {
      StringBuilder sb = new StringBuilder(11);
      for (int j = 0; j < 10; j++) {
        if (j == 5) {
          sb.append('-');
        }
        sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      }
      out.add(sb.toString());
    }
    return out;
  }

  /** Lower-case, no spaces or dashes; null when it cannot be a recovery code. */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.strip().toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
    if (s.length() != 10) {
      return null;
    }
    for (char c : s.toCharArray()) {
      if (ALPHABET.indexOf(c) < 0) {
        return null;
      }
    }
    return s;
  }
}
