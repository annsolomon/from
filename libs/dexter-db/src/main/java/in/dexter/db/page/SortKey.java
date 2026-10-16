package in.dexter.db.page;

import java.util.regex.Pattern;

/** One ORDER BY column of a keyset sort. {@code column} is a plain (optionally table-qualified) column. */
public record SortKey(String column, KeyType type) {
  private static final Pattern COL = Pattern.compile("([a-z_][a-z0-9_]*\\.)?[a-z_][a-z0-9_]*");

  public SortKey {
    if (column == null || !COL.matcher(column).matches()) {
      throw new IllegalArgumentException("invalid sort column: " + column);
    }
  }
}
