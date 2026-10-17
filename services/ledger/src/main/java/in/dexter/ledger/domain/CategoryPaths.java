package in.dexter.ledger.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Category paths are ltree labels joined by dots: lower-case {@code [a-z][a-z0-9_]*}, at most 4 levels
 * ({@code food.dining}). The first label is the root (daily totals are kept per root).
 */
public final class CategoryPaths {
  public static final String DEFAULT = "general";
  public static final int MAX_LEVELS = 4;
  private static final Pattern PATH = Pattern.compile("[a-z][a-z0-9_]{0,31}(\\.[a-z][a-z0-9_]{0,31}){0,3}");

  private CategoryPaths() {}

  public static boolean isValid(String path) {
    return path != null && PATH.matcher(path).matches();
  }

  public static String root(String path) {
    int dot = path.indexOf('.');
    return dot < 0 ? path : path.substring(0, dot);
  }

  public static int levels(String path) {
    return path.split("\\.").length;
  }

  /** {@code "Pet care & vet!"} → {@code "pet_care_vet"}; null when nothing usable remains. */
  public static String slug(String name) {
    if (name == null) {
      return null;
    }
    String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (ascii.isEmpty()) {
      return null;
    }
    if (!Character.isLetter(ascii.charAt(0))) {
      ascii = "c_" + ascii;
    }
    return ascii.length() > 32 ? ascii.substring(0, 32).replaceAll("_+$", "") : ascii;
  }
}
